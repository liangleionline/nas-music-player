# Keep Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# v1.4.84 首次 R8 混淆即闪退：CompositionLocal LocalLifecycleOwner not present（MainActivity 组合 HomeRoot 时）。
# 根因：R8 混淆破坏了 Compose 的 ViewTree owner 提供链（AndroidComposeView -> ViewTreeLifecycleOwner）。
# 修复：Compose / Lifecycle / Activity / Navigation / DrawerLayout / Coil / Guava 全保
#（只裁剪未用代码、不混淆改名；R8 仍做 tree-shaking，大小收益主要来自资源压缩 + 未用代码裁剪 + 自有代码混淆）。

# Compose UI / runtime / material3 / foundation / animation
-keep class androidx.compose.** { *; }
-keep interface androidx.compose.** { *; }

# Lifecycle（含 LocalLifecycleOwner、ViewTreeLifecycleOwner 桥接链）
-keep class androidx.lifecycle.** { *; }
-keep interface androidx.lifecycle.** { *; }

# Activity / Navigation / DrawerLayout（MainActivity / 抽屉 View 层）
-keep class androidx.activity.** { *; }
-keep class androidx.navigation.** { *; }
-keep class androidx.drawerlayout.** { *; }

# Coil（ImageLoader.execute 内部经 Guava Futures 回调组合上下文）
-keep class coil.** { *; }
-keep class com.google.common.** { *; }

# 自有核心类（兜底）
-keep class com.lianglei.nasmusic.App { *; }
-keep class com.lianglei.nasmusic.player.PlayerService { *; }
-keep class com.lianglei.nasmusic.player.PlayerManager { *; }

# 保留泛型/注解元信息
-keepattributes Signature, InnerClasses, EnclosingMethod

# Guava 反射代码引用 Java 8 API（Android 无），标准 suppression
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn javax.lang.model.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
