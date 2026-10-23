# 飞牛 fnOS 音乐服务接入技术要点

本文档总结 NAS Music Player 从飞牛 fnOS 音乐服务（Music API v1）获取数据的完整技术路径：**登录验证、音频 Tag 信息、目录信息、播放流**，以及接入过程中踩过的坑。其他需要对接 fnOS 音乐服务的项目可直接参考本文件与 `app/src/main/java/com/lianglei/nasmusic/data/FnApi.kt` 的实现。

> 适用对象：Android / JVM / 任意 HTTP 客户端对接 fnOS（fnOS 系统自带音乐服务）的开发者。
> 注意：以下接口来自实际抓包验证，非飞牛官方开放平台文档（官方开放平台的 TRIM_API_TOKEN / 统一网关 / JS SDK 等仅面向**平台内应用**，无安卓外部客户端文件浏览接口，因此本方案走的是 NAS 上实际运行的 Music 服务 HTTP 接口）。

---

## 0. 接入总览

| 能力 | 接口 | 方式 |
| --- | --- | --- |
| 登录验证 | `POST /music/api/v1/user/password-login` | 密码 SHA-256 + deviceId |
| 音频 Tag 信息 | `GET /music/api/v1/track/list`（分页） | 响应内嵌 title / artists / album / genres / duration / coverId 等 |
| 目录信息 | 同上（`track.audioSpec.path` 字段） | 客户端按路径分段构建目录树 |
| 播放流 | `GET /music/api/v1/track/stream?guid=<guid>` | 流式音频，鉴权走 Authorization 头 |
| 封面 | `GET /music/api/v1/static/cover?coverId=<coverId>` | coverId 形如 `track_xxx` / `album_xxx` / `artist_xxx` |
| 歌单 | `GET /music/api/v1/playlist/list` / `.../track/playlist-detail/list` | 分页拉取歌单与歌单曲目 |

**鉴权方式**：登录拿到 `data.userToken` 后，后续所有请求在 `Authorization` 头里**直接放 token 原文**（不是 `Bearer <token>`）。

---

## 1. 服务器地址与配置解析

用户输入的地址形式多样，统一按以下规则解析：

```kotlin
// 输入示例：192.168.1.100 / nas.example.com / https://nas.example.com:2443
// 无 scheme 时默认 http；无端口时默认 5666
// API 前缀固定为 /music/api/v1
baseUrl = "<scheme>://<host>:<port>/music/api/v1"
```

超时建议：connectTimeout 20s、readTimeout 30s（NAS 首次冷启动/扫描时响应可能很慢，可适当放宽）。

---

## 2. 用户登录验证

```
POST {baseUrl}/user/password-login
Content-Type: application/json
```

请求体：

```json
{
  "username": "admin",
  "password": "<sha256Hex(明文密码)>",
  "deviceId": "<客户端生成的UUID>"
}
```

- `password` 是明文密码的 **SHA-256 十六进制小写** 摘要，不是明文、不是 base64。
- `deviceId` 每次登录可随机生成（UUID），用于服务端设备识别，非必传但建议带。

响应（code=0 成功）：

```json
{
  "code": 0,
  "msg": "",
  "data": {
    "userToken": "<长token，后续所有请求的鉴权凭证>",
    "...": "..."
  }
}
```

**关键点**：
- 判断成功以 `code == 0` 为准；`msg` 携带错误描述。
- token 持久化后可跨进程重启恢复会话（直接保存 baseUrl + token，重启后免登录）。

---

## 3. 鉴权头（易踩坑）

```kotlin
// ✅ 正确：直接放 token 原文
header("Authorization", token)

// ❌ 错误：加 Bearer 前缀会导致 401/无权限
header("Authorization", "Bearer $token")
```

飞牛 Music 服务校验的是**裸 token**。如果从别的项目习惯带 `Bearer `，务必去掉。

---

## 4. 音频 Tag 信息（曲目列表）

分页拉取全库曲目：

```
GET {baseUrl}/track/list?page=<page>&size=100&sort=createdAt,desc
Authorization: <token>
```

- 每页固定 `size=100`；响应 `data.total` 为总数，`data.list` 为当前页数组，**循环直到累计数 ≥ total 或 list 为空**。
- **必须带稳定的排序参数**（如 `sort=createdAt,desc`），否则翻页过程中数据漂移会漏/重。
- 时间字段是**秒级 Unix 时间戳**（如 `createdAt: 1792637532`），不是毫秒。

单条 track 完整结构（真实响应，已脱敏）：

```json
{
  "guid": "1c17722648a643e09e02ffe7492ae439",
  "title": "你不要那样看着我的眼睛",
  "coverId": "track_c5029b3ae008427b89195fc56bab8c32",
  "year": 1997,
  "discNo": null,
  "trackNo": null,
  "isrc": null,
  "duration": 273005,
  "isCue": false,
  "createdAt": 1792637532,
  "updatedAt": 1792637532,
  "album": {
    "guid": "e5e4d075dbf5429f9688c0e5a130a059",
    "name": "你不要那样看着我的眼睛",
    "coverId": "track_c5029b3ae008427b89195fc56bab8c32",
    "releaseDate": null,
    "barcode": null,
    "createdAt": 1792637532,
    "updatedAt": 1792637532
  },
  "artists": [
    { "guid": "1a693ab6ea8943919ee15d972903def8", "name": "蔡琴", "coverId": "track_de243efd6be04045a61e1f26cc13056d", "createdAt": 1787566851, "updatedAt": 1787566851 }
  ],
  "genres": [
    { "guid": "b98aecae4c414d52a99384a32a6ead4c", "name": "Blues", "coverId": null, "createdAt": 1787566798, "updatedAt": 1787566798 }
  ],
  "audioSpec": {
    "bitDepth": 32,
    "sampleRate": 44100,
    "channel": 2,
    "bitrate": 320000,
    "codec": "mp3",
    "container": "",
    "duration": 273005,
    "format": "mp3",
    "path": "/vol1/1000/Resources/Media/Music/1.中文/蔡琴 - 你不要那样看着我的眼睛.mp3",
    "size": 10951589
  },
  "isFavorite": false
}
```

**Tag 信息映射**（客户端取用规则）：

| 曲目字段 | 说明 |
| --- | --- |
| `guid` | 曲目唯一 ID，播放流与队列操作都靠它 |
| `title` | 歌名（Tag 已由服务端解析好） |
| `artists[0].name` | 主歌手（数组可能多人） |
| `album.name` / `album.guid` | 专辑名 / 专辑 ID |
| `genres[].name` | 流派（可能多个） |
| `coverId` | 封面 ID，拼 `static/cover?coverId=` 取图 |
| `duration` | 时长（毫秒） |
| `year` / `discNo` / `trackNo` / `isrc` | 年份 / 碟号 / 音轨号 / ISRC（可能为 null） |
| `isCue` | 是否为 CUE 分段 |
| `audioSpec.codec/format` | 编码格式，可用于标记 FLAC 等无损 |

> 服务端已在入库时解析好音频文件的内嵌 Tag，客户端无需再解析文件本身。

---

## 5. 目录信息（文件夹树，关键）

**目录信息来源**：曲目对象里的 **`audioSpec.path`**（嵌套字段）——这是该音频在 NAS 上的真实文件系统路径：

```
/vol1/1000/Resources/Media/Music/1.中文/蔡琴 - 你不要那样看着我的眼睛.mp3
```

路径结构：`/vol1/<用户ID>/Resources/Media/Music/<分类目录...>/<文件名>`。

### 5.1 字段探测优先级

服务器不同版本字段位置可能变化，客户端做了多级兜底探测：

1. **`audioSpec.path`**（实测命中源，真实响应确认）
2. track 顶层常见字段名（`path` / `relativePath` / `sourcePath` / `filePath` / `audioPath` / `dirPath`）
3. `audioSpec` 对象内扫描"含 `/` 且非 URL 的字符串"（应对字段改名）
4. track 顶层扫描（同上规则）
5. `url` 字段剥出路径段（最差兜底）

### 5.2 系统前缀剥离

`/vol1/1000/Resources/Media/Music/` 是 NAS 音乐库的**系统前缀**，必须剥掉，否则目录树顶层会出现 `vol1 → 1000 → Resources → Media → Music` 五层无意义目录。

```kotlin
/** 剥离 NAS 系统前缀，保留音乐库内相对路径（如 "1.中文/歌手 - 歌名.mp3"） */
private fun relPathOf(p: String): String {
    val marker = "/Resources/Media/Music/"
    val idx = p.indexOf(marker)
    return if (idx >= 0) p.substring(idx + marker.length) else p.trim('/')
}
```

剥离后 folder 存相对路径 → **顶层目录即音乐库真实分类**（`1.中文`、`动画对白`、`鬼灭之刃 OST`、`4.其他语种` 等）。

### 5.3 客户端构建多级目录树

拿全部 track 的 folder 字段后，纯客户端分段建树（无需额外接口）：

- 每首歌 folder 按 `/` 分段，如 `["1.中文", "蔡琴 - 你的眼神.mp3"]`
- 最后一个段是**文件名**，之前的段是**目录层级**
- 逐层归并：`目录 A / 目录 B / ...` → 树
- 展示逻辑：当前路径段列表 + 该目录下子目录列表 + 该目录下歌曲列表
- 面包屑记录路径，支持逐层返回

```kotlin
fun dirSegsOf(folder: String): List<String> {
    if (folder.isBlank() || folder == "飞牛NAS") return emptyList()
    val segs = folder.trim('/').split('/').filter { it.isNotEmpty() }
    // 最后一个段是文件名，目录段 = 去掉最后一段
    return if (segs.size <= 1) emptyList() else segs.dropLast(1)
}
```

播放仍走 `guid` 流，目录只做导航，不涉及文件传输。

---

## 6. 播放流

```
GET {baseUrl}/track/stream?guid=<曲目guid>
Authorization: <token>
```

- 返回流式音频（与音频格式一致，如 mp3/flac），直接用 ExoPlayer/Media3 等播放器设置 dataSource URI 即可。
- 客户端 `Song.data` 存的就是这个 URL；换歌只换 guid。
- 建议在播放器里透传 `Authorization` 头（部分播放器需自定义 DataSource 支持）。

**封面图**：

```
GET {baseUrl}/static/cover?coverId=<coverId>
Authorization: <token>
```

coverId 直接来自 track 对象（`track_xxx` 前缀），Coil/Glide 加载时同样需要带鉴权头（用自定义 HeadersInterceptor 或 OkHttp 拦截器统一加）。

---

## 7. 歌单接口

```
GET {baseUrl}/playlist/list?page=1&size=100
GET {baseUrl}/track/playlist-detail/list?playlistGUID=<guid>&page=<page>&size=100&sort=trackAddedAt,desc
```

- 歌单列表字段：`guid / name / coverId / trackCount`
- 歌单内曲目结构与全库曲目一致（同样含 `audioSpec.path`），复用同一套解析。

---

## 8. 踩坑记录

1. **路径藏在嵌套字段**：`audioSpec.path` 是嵌套对象里的字段，只扫 track 顶层永远拿不到 → 目录树 0 目录。必须递归/逐层探测。
2. **Authorization 不要 Bearer**：直接裸 token。
3. **分页必须排序**：`sort=createdAt,desc` 防翻页漂移。
4. **同 versionCode 覆盖不可靠**：Android 调试期升级必须递增 versionCode，并在日志中自证版本，否则用户装的是旧代码，问题排查会走弯路。
5. **时间戳是秒级**：`createdAt`/`updatedAt` 是 Unix 秒，不是毫秒（毫秒值会显示为未来时间）。
6. **官方开放平台 ≠ 外部客户端**：飞牛开放平台的 TRIM_API_TOKEN、`/var/apps`、统一网关、JS SDK 只面向平台内应用；安卓外部客户端直接对接 NAS 上运行的 Music 服务 HTTP 接口即可，无需（也无法）走开放平台。

---

## 9. 相关代码

| 文件 | 内容 |
| --- | --- |
| `app/src/main/java/com/lianglei/nasmusic/data/FnApi.kt` | 全部 API 客户端：配置解析 / 登录 / 拉取曲目 / 歌单 / 封面 URL / 路径探测与前缀剥离 |
| `app/src/main/java/com/lianglei/nasmusic/ui/screens/Screens.kt` | `FolderBrowserScreen`：目录树构建与逐层浏览 UI |

许可证：本文档随项目以 GPL-3.0 发布。
