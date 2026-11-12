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
- **播放缓存**：NAS 流式播放自动落盘缓存（Media3 SimpleCache，LRU 约 200MB ≈ 20 首），重开 APP 再听命中缓存秒开免重新加载；设置页实时显示已用缓存并支持一键清空
- **播放模式**：列表循环 / 单曲循环 / 随机播放 / 顺序播放 四态一键切换，切换后 Toast 提示当前模式；模式在换绑 MediaSession、APP 重启后都会恢复，界面图标与实际播放行为始终一致
- **队列与「下一首」**：点歌统一把“当前页面看到的列表”交给播放器（歌曲页 / 艺术家 / 专辑 / 文件夹 / 统计页 / 歌单都传自身列表），上一首、下一首都严格按该列表顺序走，不会再出现“点的是列表第 3 首、播的却是别的歌”
- **定时关闭**：时间选择器（小时 + 分钟），倒计时到点自动停止播放并退出应用
- **均衡器**：一键跳转系统均衡器设置页
- **推动式侧边抽屉**：全屏手势呼出导航，抽屉推动式展开（对齐 Salt Player 交互）
- **媒体库管理**：歌曲 / 专辑 / 艺术家 / 文件夹 / 歌单，支持搜索、排序
- **排序交互**：四个列表页统一排序面板——点未选中的项＝换排序字段并保留当前正逆序，点已选中的项＝翻转正逆序，右侧 ↑/↓ 标注方向
- **排序记忆**：排序方式（字段 + 方向）按页面分别写入本地 `ui_sort`，退出 APP 再进来仍按上次的选择展示
- **「添加时间」真值**：按服务端 `createdAt` 秒级时间戳排序（最新在前），不再沿用接口返回顺序，与“按名称排序”区分明确
- **飞牛文件夹树**：按服务器返回的音乐文件路径构建多级目录树，逐层浏览 NAS 上按文件夹存放的音乐（顶层为音乐库真实分类目录），目录内歌曲可直接播放、加入下一首
- **下一首播放**：歌曲列表行 "+" 按钮把歌曲插入到当前曲目下一首；已在队列中则立即提到下一首；队列为空时添加第一首自动开始播放
- **统计页**：按播放次数从高到低排列歌曲（累计播放满 15 秒计一次），随时查看最爱歌曲
- **歌曲信息**：播放页 "更多" 按钮弹出歌曲信息底部弹窗（先半屏、上划展开全屏），展示标题 / 艺术家 / 专辑 / 来源 / 播放次数 / 时长 / 比特率 / 采样率 / 文件大小 / 文件格式 / 所在目录
- **NAS 断线自愈**：播放中断自动重试 5 次，仍失败弹窗询问继续重试或一键切回本地模式
- **播放进度持久化**：换歌 / 点歌 / 播放位置（每 30s）自动保存队列与进度，进程被终止后重启自动恢复播放列表继续听
- **专辑信息真值**：专辑页年份与采样率从 MediaStore / 音频标签读取真实值，不再显示占位
- **中文文案资源化**：全部 UI 文案抽入 `res/values/strings.xml`，为多语言做准备
- **播放页独立 Activity**：弹出 / 收起走系统窗口动画（SurfaceFlinger 图层变换），弱 GPU 设备上依然丝滑

## 安装

1. 在 [Releases](https://github.com/liangleionline/nas-music-player/releases/latest) 下载最新 `NAS-Music-vX.Y.Z.apk`（当前约 9.3 MB，release 包）
2. 手机允许“安装未知来源应用”后安装，支持 Android 8.0+
3. 首次启动：本地音乐需授予音频读取权限；NAS 音源在设置页填写飞牛 fnOS 服务器地址与账号登录

## 版本与更新日志

最新版本：见顶部 `version` 徽章（当前 **v1.5.5**，versionCode 185）

Release 页面每次发版保留**最新 3 个版本**的 APK，更早的版本连同说明一起清理；累计下载量与最新版下载量看顶部 `downloads`、`latest downloads` 徽章，单个版本精确次数可在 Release 页对应 APK 右侧查看。

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
