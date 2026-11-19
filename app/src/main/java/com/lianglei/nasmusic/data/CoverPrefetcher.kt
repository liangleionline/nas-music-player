package com.lianglei.nasmusic.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 封面后台预取（v1.5.9）。
 *
 * 只在"正在播放 + 当前是飞牛音源 + 网络是 WiFi/有线"时启动：把曲库里所有封面按 3 路并发
 * 抓到 Coil 的磁盘缓存里。之后列表页、播放页的封面都直接命中本地缓存，不会边滚边等网络，
 * 也就不会出现"一张一张慢慢冒出来"的观感。
 *
 * - 已抓取过的封面 id 记录在 `filesDir/cover_prefetch_done.txt`，跨进程续传，不会每次从头再来
 * - 磁盘缓存写满 [MAX_CACHE_BYTES] 后自动停下，不吃光设备空间
 * - 连续失败 [MAX_FAIL_STREAK] 次（例如 NAS 掉线）暂停一分钟再继续，避免空转打网络
 */
object CoverPrefetcher {
    private const val PREFS = "cover_prefetch"
    private const val KEY_ENABLED = "enabled"
    private const val DONE_FILE = "cover_prefetch_done.txt"

    /** 并发路数：3 路足够跑满家用 WiFi，又不至于把 NAS 的小水管抢空影响播放 */
    private const val WORKERS = 3
    private const val THROTTLE_MS = 30L
    /** 单张封面的失败容忍度：超过就跳过它，避免个别坏图把一路 worker 卡死 */
    private const val MAX_FAIL_PER_ID = 3
    /** 设备剩余空间低于这个值就收手，不去占满存储 */
    private const val MIN_FREE_BYTES = 500L * 1024 * 1024
    private const val MAX_FAIL_STREAK = 5
    /** 预取请求的边长：列表/播放页封面都是小图，只解码到这个尺寸，省 CPU 也省内存 */
    private const val REQUEST_SIZE = 160

    private var appCtx: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val done = ConcurrentHashMap.newKeySet<String>()
    /** 本次运行里每张封面的连续失败次数，达到 [MAX_FAIL_PER_ID] 就跳过 */
    private val failed = ConcurrentHashMap<String, Int>()
    @Volatile private var doneLoaded = false

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** 已缓存封面数 / 封面总数 */
    private val _progress = MutableStateFlow(0 to 0)
    val progress: StateFlow<Pair<Int, Int>> = _progress.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _enabled.value = p.getBoolean(KEY_ENABLED, true)
        scope.launch {
            runCatching {
                val f = File(ctx.filesDir, DONE_FILE)
                if (f.exists()) f.forEachLine { line -> if (line.isNotBlank()) done.add(line.trim()) }
            }
            doneLoaded = true
            reportProgress()
        }
    }

    /** 设置页开关：关掉后立即停止后台抓取，已缓存的封面仍可正常命中 */
    fun setEnabled(ctx: Context, on: Boolean) {
        _enabled.value = on
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) start() else stop()
    }

    /** 播放开始 / 用户手动触发时调用；重复调用无副作用 */
    fun start() {
        if (!_enabled.value) return
        if (job?.isActive == true) return
        if (appCtx == null) return
        job = scope.launch { run() }
    }

    fun stop() {
        job?.cancel()
        job = null
        _running.value = false
    }

    private suspend fun run() {
        val ctx = appCtx ?: return
        if (SourceManager.current.value != MusicSource.FEINIU) return
        if (FnApi.baseUrl.isBlank() || FnApi.token.isBlank()) return
        val ids = SourceManager.songs.value
            .mapNotNull { it.coverId.takeIf { c -> c.isNotBlank() } }
            .distinct()
        if (ids.isEmpty()) return
        val loader = coil.Coil.imageLoader(ctx)
        failed.clear()
        _running.value = true
        try {
            // 缓存目录被系统清过（或用户清了应用缓存）时，done 记录也要一起作废，
            // 否则会把"已缓存"当成已完成，却永远不再去抓
            if (loader.diskCache?.size == 0L && done.isNotEmpty()) {
                done.clear()
                runCatching { File(ctx.filesDir, DONE_FILE).delete() }
            }
            // 没有 WiFi 就先等着，不消耗蜂窝流量；但最多等 10 分钟（v1.5.11 起开屏就会触发一次，
            // 不能在没有 WiFi 的场景下无限空转），到点收工，下次开屏/开播或切到飞牛音源再续
            var startWait = 0L
            while (!onWifi(ctx)) {
                if (!_enabled.value) return
                delay(10_000)
                startWait += 10_000
                if (startWait >= 10 * 60_000L) return
            }
            while (!doneLoaded) delay(200)

            val cachedCount = AtomicInteger(ids.count { it in done })
            _progress.value = cachedCount.get() to ids.size

            val queue = Channel<String>(Channel.UNLIMITED)
            ids.forEach { queue.trySend(it) }
            queue.close()
            val failStreak = AtomicInteger(0)

            coroutineScope {
                repeat(WORKERS) {
                    launch {
                        while (true) {
                            val id = queue.receiveCatching().getOrNull() ?: break
                            if (!isActive || !_enabled.value) break
                            if (id in done) continue
                            // 中途断网：等恢复；超过 10 分钟仍没网就收工，等下次播放再续
                            var waited = 0L
                            while (!onWifi(ctx)) {
                                if (!isActive || !_enabled.value) return@launch
                                delay(10_000)
                                waited += 10_000
                                if (waited >= 10 * 60_000L) return@launch
                            }
                            // v1.5.15：不再看"Coil 磁盘缓存用满多少"就整体退出。
                            // 旧逻辑以 512MB 为界，缓存顶满后 3 路 worker 全部 return，进度就永久冻在 8960/12267。
                            // 现在只按设备剩余空间兜底，余量低于 MIN_FREE_BYTES 才收手。
                            if (freeBytes(ctx) < MIN_FREE_BYTES) return@launch
                            // 个别封面反复取不到（服务端 404/坏图）时跳过，不让它反复耗掉一路 worker
                            if ((failed[id] ?: 0) >= MAX_FAIL_PER_ID) continue

                            val ok = fetch(ctx, loader, id)
                            if (ok) {
                                failStreak.set(0)
                                failed.remove(id)
                                if (done.add(id)) {
                                    appendDone(ctx, id)
                                    _progress.value = cachedCount.incrementAndGet() to ids.size
                                }
                                delay(THROTTLE_MS)
                            } else {
                                failed[id] = (failed[id] ?: 0) + 1
                                if (failStreak.incrementAndGet() >= MAX_FAIL_STREAK) {
                                    failStreak.set(0)
                                    delay(60_000)
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            _running.value = false
            reportProgress()
        }
    }

    private suspend fun fetch(ctx: Context, loader: ImageLoader, coverId: String): Boolean {
        val request = ImageRequest.Builder(ctx)
            .data(FnApi.coverUrl(coverId))
            .size(REQUEST_SIZE)
            // 预取只为把原始字节写进磁盘缓存，小图不进内存缓存，避免挤掉界面上正在用的大图
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
        return runCatching { loader.execute(request) }
            .map { it is SuccessResult }
            .getOrDefault(false)
    }

    private fun reportProgress() {
        val ids = SourceManager.songs.value
            .mapNotNull { it.coverId.takeIf { c -> c.isNotBlank() } }
            .distinct()
        _progress.value = ids.count { it in done } to ids.size
    }

    private fun appendDone(ctx: Context, id: String) {
        runCatching { File(ctx.filesDir, DONE_FILE).appendText(id + "\n") }
    }

    private fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** 应用私有目录所在分区的可用空间 */
    private fun freeBytes(ctx: Context): Long = runCatching {
        android.os.StatFs(ctx.filesDir.absolutePath).availableBytes
    }.getOrDefault(Long.MAX_VALUE)
}
