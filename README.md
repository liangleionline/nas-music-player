# NAS Music Player

基于 Android Jetpack Compose 开发的开源音乐播放器，支持**本地音乐**与**飞牛 fnOS NAS** 双音源播放，界面结构与交互方式参考 Salt Player 设计。

## 功能

- **双音源**：本地音乐扫描播放；飞牛 fnOS NAS 登录后流式播放、封面加载
- **播放体验**：播放队列、迷你播放栏、歌词面板（内嵌歌词 + `.lrc` 文件歌词解析）、卡拉 OK 逐字高亮、中日歌词分行（日文一行 + 中文翻译下一行）
- **播放页背景**：KMeans 从封面提取主色，生成缓慢缭绕的彩色迷幻烟雾动画（随歌曲封面自动变色）
- **封面 / 歌词切换**：播放页封面与歌词面板玻璃跟手平移切换，双向手势同步
- **播放模式**：列表循环 / 单曲循环 / 随机播放 / 顺序播放 四态一键切换
- **定时关闭**：时间选择器（小时 + 分钟），倒计时到点自动停止播放并退出应用
- **均衡器**：一键跳转系统均衡器设置页
- **推动式侧边抽屉**：全屏手势呼出导航，抽屉推动式展开（对齐 Salt Player 交互）
- **媒体库管理**：歌曲 / 专辑 / 艺术家 / 文件夹 / 歌单，支持搜索、排序
- **飞牛文件夹树**：按服务器返回的音乐文件路径构建多级目录树，逐层浏览 NAS 上按文件夹存放的音乐（顶层为音乐库真实分类目录），目录内歌曲可直接播放、加入下一首
- **下一首播放**：歌曲列表行 "+" 按钮把歌曲插入到当前曲目下一首；已在队列中则立即提到下一首；队列为空时添加第一首自动开始播放

## 版本

最新版本：**v1.4.50**（versionCode 131）

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
