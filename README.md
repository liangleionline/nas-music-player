# NAS Music Player

[![Latest release](https://img.shields.io/github/v/release/liangleionline/nas-music-player?label=version&sort=semver&color=2563eb)](https://github.com/liangleionline/nas-music-player/releases/latest)
[![Total downloads](https://img.shields.io/github/downloads/liangleionline/nas-music-player/total?label=downloads&color=16a34a)](https://github.com/liangleionline/nas-music-player/releases)
[![Latest release downloads](https://img.shields.io/github/downloads/liangleionline/nas-music-player/latest/total?label=latest%20downloads&color=16a34a)](https://github.com/liangleionline/nas-music-player/releases/latest)
[![Stars](https://img.shields.io/github/stars/liangleionline/nas-music-player?color=eab308)](https://github.com/liangleionline/nas-music-player/stargazers)
[![Last commit](https://img.shields.io/github/last-commit/liangleionline/nas-music-player?label=last%20commit)](https://github.com/liangleionline/nas-music-player/commits/main)
[![License](https://img.shields.io/github/license/liangleionline/nas-music-player?color=blue)](https://github.com/liangleionline/nas-music-player/blob/main/LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3ddc84)](https://github.com/liangleionline/nas-music-player/releases)

基于 Android Jetpack Compose 开发的开源音乐播放器，支持**本地音乐**与**飞牛 fnOS NAS** 双音源播放，界面结构与交互方式参考 Salt Player 设计。支持 Android 8.0（API 26）及以上，日常在三星 S24 真机验收。

## 功能

- **双音源**：本地音乐扫描播放；飞牛 fnOS NAS 登录后流式播放、封面加载
- **播放体验**：播放队列、迷你播放栏、歌词面板（内嵌歌词 + `.lrc` 文件歌词解析，内嵌歌词支持 MP3 ID3v2 的 USLT/SYLT、FLAC Vorbis Comment、M4A iTunes ©lyr，本地文件与飞牛 NAS 远端歌曲均可识别）、卡拉 OK 逐字高亮、中日歌词分行（日文一行 + 中文翻译下一行）
- **播放页背景**：从封面取色生成缓慢缭绕的彩色迷幻烟雾动画，随歌曲封面自动变色（先剔除封面的黑底、白边、灰背景，再按鲜艳度聚类取色，灰调封面不会糊成同一团灰），全屏连续渐变无黑洞/无彩条
- **播放页切歌不跳**：切歌时底色与烟雾配色 400ms 渐变过渡、新封面下载完成前保留上一张、歌词不再清空重排；控制键一行固定高度，缓冲状态变化不会顶动封面与进度条
- **封面 / 歌词切换**：播放页封面与歌词面板玻璃跟手平移切换，双向手势同步
- **音乐缓存**：NAS 流式播放自动落盘缓存（Media3 SimpleCache，LRU，上限默认 200MB ≈ 20 首，**可在设置页自定 50–4096MB，改完即时生效不打断播放**），重开 APP 再听命中缓存秒开免重新加载；设置页实时显示「占用空间 已用/上限」并支持一键清空
- **播放模式**：列表循环 / 单曲循环 / 随机播放 / 顺序播放 四态一键切换，切换后 Toast 提示当前模式；模式在换绑 MediaSession、APP 重启后都会恢复，界面图标与实际播放行为始终一致
- **队列与「下一首」**：点歌统一把“当前页面看到的列表”交给播放器（歌曲页 / 艺术家 / 专辑 / 文件夹 / 统计页 / 歌单都传自身列表），上一首、下一首都严格按该列表顺序走，不会再出现“点的是列表第 3 首、播的却是别的歌”
- **定时关闭**：时间选择器（小时 + 分钟），倒计时到点自动停止播放并退出应用
- **均衡器**：一键跳转系统均衡器设置页
- **推动式侧边抽屉**：全屏任意位置右滑呼出导航，抽屉**跟手同步拉出**（手指拖到哪抽屉到哪），右侧主页面被抽屉**同步推开一个抽屉宽度**；松手时露出不足 1/3 自动弹回、超过 1/3 顺势展开
- **媒体库管理**：歌曲 / 专辑 / 艺术家 / 文件夹 / 歌单，支持搜索、排序
- **排序交互**：四个列表页统一排序面板——点未选中的项＝换排序字段并保留当前正逆序，点已选中的项＝翻转正逆序，右侧 ↑/↓ 标注方向
- **排序记忆**：排序方式（字段 + 方向）按页面分别写入本地 `ui_sort`，退出 APP 再进来仍按上次的选择展示
- **「添加时间」真值**：按服务端 `createdAt` 秒级时间戳排序（最新在前），不再沿用接口返回顺序，与“按名称排序”区分明确
- **飞牛文件夹树**：按服务器返回的音乐文件路径构建多级目录树，逐层浏览 NAS 上按文件夹存放的音乐（顶层为音乐库真实分类目录），目录内歌曲可直接播放、加入下一首
- **下一首播放**：歌曲列表行 "+" 按钮把歌曲插入到当前曲目下一首；已在队列中则立即提到下一首；队列为空时添加第一首自动开始播放
- **统计页**：按播放次数从高到低排列歌曲（累计播放满 15 秒计一次），随时查看最爱歌曲
- **歌曲信息**：播放页 "更多" 按钮弹出歌曲信息底部弹窗（先半屏、上划展开全屏），展示标题 / 艺术家 / 专辑 / 来源 / 播放次数 / 时长 / 比特率 / 采样率 / 文件大小 / 文件格式 / 所在目录
- **NAS 断线自愈**：播放中断自动重试 5 次，仍失败弹窗询问继续重试或一键切回本地模式
- **检查更新**：每次冷启动自动查本仓库最新 Release（`api.github.com/.../releases/latest`），比 `versionName` 有更高版本就弹窗提示版本号与更新说明；设置页「检查更新」可手动再查，已是最新会明确提示。点「立即更新」按 GitHub Release 直链下载 APK，下完直接拉起系统安装器覆盖安装
- **底部播放条常驻**：迷你播放条固定贴在屏幕底部横跨整屏，抽屉跟手拉出、推动页面时它原地不动，任何页面 / 抽屉开合状态下位置都不变
- **播放进度持久化**：换歌 / 点歌 / 退到后台 / 播放位置（每 30s）都会同步落盘队列与进度；冷启动（含装新版 APK 后的首次启动）自动恢复上次的播放列表与进度，恢复动作由播放器自己等 MediaSession 连接与曲库就绪后执行，不再受启动时序影响
- **专辑信息真值**：专辑页年份与采样率从 MediaStore / 音频标签读取真实值，不再显示占位
- **专辑碟号分组**：专辑详情页按碟号分成 CD1 / CD2…，没有碟号标签的收进 `＃` 组；组内按音轨号排序，缺音轨号按标题、标题为空按文件名。碟号/音轨号优先取服务端字段，服务端返回 null 时客户端自己读 ID3/FLAC 标签兜底
- **封面缓存**：播放中且连的是 WiFi / 有线网络时，后台按 3 路并发把曲库封面全量抓进本地磁盘缓存（上限 1GB，够覆盖一万两千张以上的曲库封面；设备剩余空间低于 500MB 自动收手），列表页与播放页的封面直接读本地，不再一张张等网络；设置页显示「已缓存 x / y 张封面 · 占用空间」、点行内可打开或关闭此功能、右侧可一键清空
- **中文文案资源化**：全部 UI 文案抽入 `res/values/strings.xml`，为多语言做准备
- **播放页独立 Activity**：弹出 / 收起走系统窗口动画（SurfaceFlinger 图层变换），弱 GPU 设备上依然丝滑

## 安装

1. 在 [Releases](https://github.com/liangleionline/nas-music-player/releases/latest) 下载最新 `NAS-Music-vX.Y.Z.apk`（当前约 9.3 MB，release 包）
2. 手机允许“安装未知来源应用”后安装，支持 Android 8.0+
3. 首次启动：本地音乐需授予音频读取权限；NAS 音源在设置页填写飞牛 fnOS 服务器地址与账号登录

## 版本与更新日志

最新版本：见顶部 `version` 徽章（当前 **v1.5.18**，versionCode 198）

Release 页面每次发版保留**最新 3 个版本**的 APK，更早的版本连同说明一起清理；累计下载量与最新版下载量看顶部 `downloads`、`latest downloads` 徽章，单个版本精确次数可在 Release 页对应 APK 右侧查看。

### v1.5.18（versionCode 198）

- **底部播放条固定不动**：抽屉推动右侧页面时，迷你播放条不再跟着页面右移，而是始终贴在屏幕底部、横跨整屏（画在抽屉图层之上）。
  - 实现：播放条从 `Scaffold.bottomBar` 移到 `HomeRoot` 最外层 `Box`，作为独立一层叠在 `DrawerLayout` 之上；`Scaffold` 底部只保留同高（56dp）占位，列表 `innerPadding` 与之前完全一致，最后一行不会被盖住。
  - 抽屉面板仍在播放条下方延伸，播放条覆盖其上，视觉上抽屉在播放条上方结束。

### v1.5.17（versionCode 197）

- **新增「检查更新」**：数据源是本仓库最新 Release（`api.github.com/repos/liangleionline/nas-music-player/releases/latest`），按点分段比较 `tag_name` 与本机 `versionName`
  - 每次冷启动 5 秒后自动查一次（失败静默，不打扰使用），有新版本在首页弹窗：版本号、当前版本、该版本更新说明
  - 设置页新增「检查更新」行：整行可点，右侧按钮手动触发；检查中按钮显示「检查中…」；已是最新 / 网络失败都会 Toast 明说
  - 弹窗点「立即更新」→ 按 Release 里的 `browser_download_url` 直链下载 APK 到 `filesDir/update/`，弹窗内显示百分比进度；下载完成通过 `FileProvider` 拉起系统安装器覆盖安装
  - 下载中断不会留半个包：先写 `download.tmp`，校验非空后再改名；已存在且大小一致的旧包直接复用，不重复下载

### v1.5.16（versionCode 196）

- 设置页文案更名：播放缓存 → **音乐缓存**，封面预取 → **封面缓存**
- 音乐缓存支持**自定义上限**：点整行弹出输入框，自行填写上限（50–4096MB）。小字改为「点击设置。/ 占用空间 已用/上限」。改动即时生效——淘汰器上限改成可变字段（`DynamicLruEvictor`），不必重建 SimpleCache，不会打断正在播放的音轨，超出新上限的部分当场按 LRU 清掉
- 封面缓存改为**整行可点**：弹出只有一个动作按钮的弹窗，文案随状态在「打开此功能 / 关闭此功能」之间切换。小字改为「已缓存 x/y 张封面，/ 占用空间 x」；关闭时显示「此功能已关闭 / 点击以开启功能」
- 封面缓存右侧新增**清空缓存**按钮：停预取 → 清 Coil 磁盘缓存 → 作废已缓存清单 → 进度归零，完成后自动续跑
- 两个缓存行的占用空间每 2 秒刷新，不再需要退出重进设置页才更新数字；显示单位自动在 MB / GB 之间切换

### v1.5.15（versionCode 195）

- 修复「封面预取」跑到 8960 / 12267 就再也不动：旧逻辑以 Coil 磁盘缓存 512MB 为预算边界，缓存顶满后 3 路 worker 全部退出、进度永久冻结。现在**不再以缓存体积为界**，只按设备剩余空间兜底（低于 500MB 才收手），封面磁盘缓存上限同步提到 1GB，可容纳全量曲库封面
- 单张封面连续失败 3 次就跳过，不再反复占用一路 worker；播放/开屏触发的每次预取都会重新给失败项一次机会
- 进度按"真正落盘的封面数"统计，不再出现进度不动但仍在空转的状态

### v1.5.14（versionCode 194）

- 抽屉拉开时**右侧主页面同步右移一个抽屉宽度**（推动式）：抽屉露出多少、主内容就右移多少，形成抽屉把页面推出去的效果；收起时按同一比例归零

### v1.5.13（versionCode 193）

- **侧边抽屉改为跟手拖动**：整屏任意位置起手右滑，抽屉随手指实时拉出（内容同步被推开），不再"手指在位时不动、抬手后补一段系统动画"
  - 把 DrawerLayout 内部拖拽器的边缘触发区从默认左缘 20dp 补成整屏宽，并每次测量/布局后重补，避免被系统手势区 insets 覆盖
  - 松手判定改为**抽屉宽度的 1/3**：≥1/3 顺势展开到底，<1/3 自动弹回（原生是 50%）
  - 抽屉打开后的左滑关闭手势保持不变

### v1.5.12（versionCode 192）

- 修复**冷启动后控制条显示「暂停键」但其实没有在播放**（上次退出时正在播放的场景）：
  - 恢复播放状态时显式 `pause()` + `prepare()`，恢复出的队列永远是"暂停"态，不会因为底层残留 `playWhenReady=true` 而出现"显示在播、实际没声音"
  - MediaSession 绑定成功、以及每 500ms 的进度 tick，都以 controller 的真实状态回写播放/缓冲标志。旧实现只依赖 `onIsPlayingChanged` 回调，冷启动 setUp 或通知栏操作时漏一次回调，按钮就会长期停在错误一侧

### v1.5.11（versionCode 191）

- 修复**歌单页有时显示「暂无歌单」、过一会又自己冒出来**：
  - 歌单列表改为本地缓存（`filesDir/feiniu_playlists.json`），开屏即读，不再等联网
  - 歌单与曲库解耦：旧实现排在整库扫描之后才拉（十几万首要上百个请求），歌单页只能干等；现在刷新时**先拉歌单**再拉曲库
  - 歌单页 / 歌单详情页在拉取期间显示加载中，只有确认服务器上确实没有歌单才显示「暂无歌单」
- 修复**刚打开 App 没有封面、要等播放后才批量出现**：
  - 冷启动不再整库重扫 NAS：曲库缓存 24 小时内直接用（旧版每次开屏都 100 条/页重拉整个曲库，NAS 被占满、界面封面请求全在排队）。需要立即同步走媒体库页「从服务器重新加载」
  - 封面预取改为**开屏 3 秒后就开始**（仍只在 WiFi/有线下真正发请求），不再等"开始播放"才动手
  - 封面请求的并发上限从 OkHttp 默认的同域名 5 路放宽到 8 路，列表里的封面不再排队一张张冒

### v1.5.10（versionCode 190）

- 修复**专辑详情页背景偏米黄**：该页原本自己铺了一层 `#F5F2EC`，而歌曲 / 专辑 / 艺术家列表页用的是全局 Scaffold 底色 `#F2F3F5`，来回切换能看出色差。现在专辑页与统计页都去掉这层自定义底色，统一跟随全局背景

### v1.5.9（versionCode 189）

- 新增**封面后台预取**：正在播放 + 当前网络是 WiFi/有线时，自动把曲库里的封面按 3 路并发抓进 Coil 磁盘缓存（上限 512MB），列表页、播放页、专辑页的封面随后全部命中本地，滚动不再一张张等网络
  - 已抓取的封面 id 记录在 `filesDir/cover_prefetch_done.txt`，进程重启后续传，不会每次从头再来
  - 中途断网会暂停等待（最长 10 分钟），单次失败连续 5 次暂停一分钟，不会空转打网络
  - 封面磁盘缓存上限从 Coil 默认值提到 512MB；缓存写满自动停下，避免吃光设备空间
  - 设置页新增「封面预取」开关与「已缓存 x / y 张」进度，可随时关闭
  - 仅预取飞牛音源的封面；本地音乐封面来自 MediaStore，本身就在本地

### v1.5.8（versionCode 188）

- 修复「冷启动后迷你播放栏一直显示‘未在播放’、不带回上次进度」，尤其是装完新版 APK 后的首次启动：
  - **恢复时机**：旧逻辑只在 UI 的 `LaunchedEffect(曲库长度)` 里试一次恢复，此时 MediaSession 往往还没连上（`controller == null`），恢复被静默跳过且不再重试。现在把恢复交给 `PlayerManager`：MediaSession 绑定成功、曲库加载完成两个条件任一后到时都会再试一次，直到恢复成功
  - **存档被覆盖**：队列为空时（冷启动扫库 / 连 NAS 期间）不再写入存档。旧实现每 30s 会用空队列覆盖上一次的进度，等于自己把存档清掉
  - **落盘时机**：`saveState()` 从 `apply()` 异步写改为 IO 线程 `commit()` 同步落盘，并在 Activity 退到后台时补存一次，装包 force-stop 时不再丢最后一次进度
  - **首帧开销**：队列重建（上万首时构造 MediaItem）改到后台线程，冷启动不再因此卡第一帧
  - **清空队列**：用户主动清空队列会同步删除存档，下次启动不会被旧快照塞回来

### v1.5.7（versionCode 187）

- 修复“专辑页分组没生效”：飞牛服务端对没写碟号/音轨号标签的文件返回 `discNo` / `trackNo` = null，此前只认服务端字段，导致整张专辑都落进同一组、看不出分组。现在服务端没给号时，客户端用 HTTP Range 只读音频头部 512 KB，自己解析 ID3v2 的 `TPOS`/`TRCK` 和 FLAC 的 `DISCNUMBER`/`TRACKNUMBER`
- 新增 `data/AudioTagReader.kt`：纯字节解析，不下载整首歌；解析结果按音频地址缓存（256 条 LRU），同一首歌反复进出专辑页不再重复请求
- 专辑页改为**任何专辑都显示组标题**（单碟专辑显示 `＃`），便于确认分组逻辑确实生效
- 修掉一个解析 bug：ID3v2 的 UTF-16 文本帧把大小端 BOM 判反，会把 `3/9` 这类音轨号解成乱码（该问题由构造 ID3/FLAC 字节的 JVM 侧断言测试发现）

### v1.5.6（versionCode 186）

- 专辑详情页曲目改为按**碟号 + 音轨号**分组排序：有碟号的按碟号升序分成 CD1、CD2…；没有碟号标签的统一进 `＃` 组，排在所有 CD 组之后
- 组内先按音轨号升序；没有音轨号的排在有号之后，按标题排序；标题为空的按文件名排序（拼音/中文按 zh_CN 排序规则）
- 曲目行号改为显示真实音轨号（无音轨号时回落到组内序号）；点歌时交给播放器的队列顺序与页面展示顺序一致，分组标题不进队列
- 飞牛曲目直接取服务端返回的 `discNo` / `trackNo`，并写入本地缓存，重启后无需重新扫库；本地文件（MediaStore 无这两列）在进专辑页时懒读标签补全

### v1.5.5（versionCode 185）

- 修复播放页烟雾背景“每首歌颜色都一样、像没从封面取色”：旧算法按像素数量取色，取到的多是封面黑底/白边/灰背景，提亮后全变成同一团灰褐。改为剔除近黑/近白/低饱和像素、按鲜艳度加权聚类、挑色相相隔 ≥26° 的三个色，并给饱和度设地板
- 封面取色改用软件位图（`allowHardware(false)`），避免硬件位图逐像素读取报错导致一直走兜底色
- 调色板按封面地址做内存缓存，同一封面反复进出播放页不再重复计算

### v1.5.4（versionCode 184）

- 修复播放页切歌时封面与播放控制条整体“上抬一下又落回”：缓冲态把控制键一行从 48dp 顶到 72dp，面板被压缩导致位移；现固定 48dp
- 顶部信息栏与底部功能条补最小/固定高度，定时关闭倒计时文字也不再顶动内容
- README 增加 shields.io 徽章（版本 / 下载量 / Star / 最近提交 / 许可证 / 平台）

### v1.5.3（versionCode 183）

- 切歌不再“闪”：底色与烟雾配色 400ms 渐变过渡，封面在新图下载完成前保留上一张，歌词不再中途清空成“暂无歌词”
- 发布策略改为保留最近 3 个版本

### v1.5.2（versionCode 182）

- 「下一首」按当前列表走：艺术家 / 专辑 / 文件夹详情 / 统计页点歌改为传入本页列表，不再是整库下标错位
- 播放模式图标与实际一致：换绑 MediaSession 后重新下发模式、APP 重启恢复上次模式、底层 shuffle 状态回写界面；切换模式弹 Toast 明确当前模式

> 更早版本（v1.5.1 及以前）的说明已随对应 Release 一起清理，需要回溯请查看对应 git tag 的提交记录。

## 构建

要求 Android SDK 34、JDK 17+（本机用 JDK 21 验证）：

```bash
./gradlew assembleDebug     # 调试包
./gradlew assembleRelease   # 发布包
```

- 调试包：`app/build/outputs/apk/debug/app-debug.apk`
- 发布包：`app/build/outputs/apk/release/app-release.apk`
- 本工程 release 构建使用本机 debug 签名（便于自用直接安装升级），若要上架应用商店需替换为正式签名

## 开源引用

- Media3 / ExoPlayer — 媒体播放引擎（Apache 2.0）
- Jetpack Compose Material3 — UI 组件库（Apache 2.0）
- Coil — 图片加载（Apache 2.0）
- Salt Player / SaltUI — UI 设计参考（GPL-3.0）
- SaltAudioTag — 音频标签读取参考（GPL-3.0）
- fn-music-tv — 飞牛音乐 API 参考

## 许可证

本项目以 **GNU General Public License v3.0（GPL-3.0）** 协议开源发布。

你可以自由使用、修改和分发本程序，但衍生作品必须同样采用 GPL-3.0 协议开源。

源代码托管于 GitHub：https://github.com/liangleionline/nas-music-player
