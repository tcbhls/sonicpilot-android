# 保留 llama.cpp JNI 原生桥接
-keep class dev.ffmpegkit.llama.** { *; }
-dontwarn dev.ffmpegkit.llama.**

# 保留领域模型与音频组件
-keep class com.sonicpilot.music.data.model.** { *; }
-keep class com.sonicpilot.music.player.** { *; }

# OkHttp / Coil / Media3 常见安全规则
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keep class androidx.media3.** { *; }
