# NAS Music Player

基于 Android Jetpack Compose 开发的开源音乐播放器，支持**本地音乐**与**飞牛 fnOS NAS** 双音源播放，界面结构与交互方式参考 Salt Player 设计。

## 功能

- **双音源**：本地音乐扫描播放；飞牛 fnOS NAS 登录后流式播放、封面加载
- **播放体验**：播放队列、迷你播放栏、歌词面板（内嵌歌词 + `.lrc` 文件歌词解析，内嵌歌词支持 MP3 ID3v2 的 USLT/SYLT、FLAC Vorbis Comment、M4A iTunes ©lyr，本地文件与飞牛 NAS 远端歌曲均可识别）、卡拉 OK 逐字高亮、中日歌词分行（日文一行 + 中文翻译下一行）
- **播放页背景**：KMeans 从封面提取主色，生成缓慢缭绕的彩色迷幻烟雾动画（随歌曲封面自动变色，全屏连续渐变无黑洞/无彩条）
- **封面 / 歌词切换**：播放页封面与歌词面板玻璃跟手平移切换，双向手势同步
- **播放缓存**：NAS 流式播放自动落盘缓存（Media3 SimpleCache，LRU 约 200MB ≈ 20 首），重开 APP 再听命中缓存秒开免重新加载；设置页实时显示已用缓存并支持一键清空
- **播放模式**：列表循环 / 单曲循环 / 随机播放 / 顺序播放 四态一键切换
- **定时关闭**：时间选择器（小时 + 分钟），倒计时到点自动停止播放并退出应用
- **均衡器**：一键跳转系统均衡器设置页
- **推动式侧边抽屉**：全屏手势呼出导航，抽屉推动式展开（对齐 Salt Player 交互）
- **媒体库管理**：歌曲 / 专辑 / 艺术家 / 文件夹 / 歌单，支持搜索、排序
- **飞牛文件夹树**：按服务器返回的音乐文件路径构建多级目录树，逐层浏览 NAS 上按文件夹存放的音乐（顶层为音乐库真实分类目录），目录内歌曲可直接播放、加入下一首
- **下一首播放**：歌曲列表行 "+" 按钮把歌曲插入到当前曲目下一首；已在队列中则立即提到下一首；队列为空时添加第一首自动开始播放
- **统计页**：按播放次数从高到低排列歌曲（累计播放满 15 秒计一次），随时查看最爱歌曲
- **歌曲信息**：播放页 "更多" 按钮弹出歌曲信息底部弹窗（先半屏、上划展开全屏），展示标题 / 艺术家 / 专辑 / 来源 / 播放次数 / 时长 / 比特率 / 采样率 / 文件大小 / 文件格式 / 所在目录
- **NAS 断线自愈**：播放中断自动重试 5 次，仍失败弹窗询问继续重试或一键切回本地模式
- **播放进度持久化**：换歌 / 点歌 / 播放位置（每 30s）自动保存队列与进度，进程被终止后重启自动恢复播放列表继续听
- **专辑信息真值**：专辑页年份与采样率从 MediaStore / 音频标签读取真实值，不再显示占位
- **中文文案资源化**：全部 UI 文案抽入 `res/values/strings.xml`，为多语言做准备
- **播放页独立 Activity**：弹出 / 收起走系统窗口动画（SurfaceFlinger 图层变换），弱 GPU 设备上依然丝滑

## 版本

最新版本：**v1.4.84**（versionCode 164，R8 混淆 + 资源压缩，APK ≈ 3.2MB）

Release 页面保留最新版本的 APK 与源码，历史版本已清理。

APK 下载：[GitHub Releases](https://github.com/liangleionline/nas-music-player/releases)

## 构建

要求 Android SDK 34、JDK 17+：

```bash
./gradlew assembleDebug
```

调试包输出：`app/build/outputs/apk/debug/app-debug.apk`

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
