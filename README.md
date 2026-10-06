# NAS Music Player

一款面向 Android 的本地音乐播放器，UI 参考 [Salt Player](https://github.com/Moriafly/SaltPlayer)。
当前阶段只做**本地音乐播放**，后续版本会接入**飞牛 NAS（fnOS）WebDAV 流媒体播放**，实现本地 + NAS 双音源统一播放。

> 仓库：https://github.com/liangleionline/nas-music-player
> 许可证：**GPL-3.0**

## 当前版本 v0.1.0

已实现（本地播放）：

- 基于 MediaStore 的本地音乐扫描（歌曲 / 专辑 / 艺术家 / 文件夹四个视图）
- 播放队列、迷你播放条、全屏播放页（专辑封面 + 进度条 + 上一首/播放/下一首）
- 后台播放服务（Media3 MediaSession），通知栏控制、音频焦点、耳机拔插自动暂停
- 媒体来源页（开始扫描、自定义文件夹、不扫描 60 秒以下音频等入口）
- 崩溃日志 / 运行日志自动写入 `/sdcard/Download/nas-music.log` 与 `nas-music-crash.log`，便于反馈问题

未实现（占位）：歌单、音乐库、统计、设置、关于等页面先放空壳，后续迭代补全。
飞牛 NAS（WebDAV 连接、远程目录浏览、远程音频流播放、远程歌词）将在 v0.2 接入。

## 调试日志

为便于排查问题，App 启动后会把关键运行日志和未捕获异常写入公共 Download 目录：

- `/sdcard/Download/nas-music.log` — 运行日志（扫库、播放事件、报错）
- `/sdcard/Download/nas-music-crash.log` — 崩溃堆栈

反馈问题时把这两个文件发回即可。

## 技术栈

- Kotlin + Jetpack Compose（Material 3）
- AndroidX Media3（ExoPlayer + MediaSessionService）— 后台播放、通知栏、音频焦点
- MediaStore — 本地音乐元数据扫描
- Coil — 专辑封面异步加载
- Coroutines / Flow — 异步扫库与状态分发

## 与 Salt Player 开源组件的关系

本项目 UI 借鉴 Salt Player 的界面布局（截图来自用户提供的 Salt Player 实际运行界面），
但当前版本**没有直接拷贝 Salt Player 主程序源码**，而是用 Android 官方等价组件重写：

| Salt Player 生态组件 | 本项目对应实现 | 说明 |
|---|---|---|
| MediaKit（播放服务封装） | AndroidX Media3 (ExoPlayer + MediaSessionService) | 系统级后台播放、通知、音频焦点 |
| SaltAudioTag（音频标签解析） | MediaStore + AlbumArt | 后续可替换为 SaltAudioTag 做更深的标签读取 |
| SaltUI（Compose 组件库） | 原生 Material3 Compose | 后续可引入 SaltUI 统一视觉 |
| LyricViewX（歌词视图） | 暂未接入 | 后续接入远程 / 本地 LRC 渲染 |

后续版本计划按需引入上述 Salt 系列独立开源库，并在本文件中补充对应的版权声明。

## 开源声明

本项目基于 GPL-3.0 发布。使用到的第三方开源组件及其许可证：

- [AndroidX / Jetpack Compose](https://developer.android.com/jetpack) — Apache-2.0
- [AndroidX Media3](https://developer.android.com/media/media3) — Apache-2.0
- [Coil](https://coil-kt.github.io/coil/) — Apache-2.0
- UI 设计参考 [Salt Player](https://github.com/Moriafly/SaltPlayer)（GPL-3.0）

## 路线图

- [x] v0.1.0 — 本地音乐播放 MVP（本版本）
- [ ] v0.2.0 — 飞牛 NAS WebDAV 接入：设备登录、目录浏览、远程音频流播放、远程歌词
- [ ] v0.3.0 — 离线缓存 NAS 歌曲、统一播放队列、均衡器
