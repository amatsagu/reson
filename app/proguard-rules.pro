# Reson ProGuard / R8 Optimization & Minification Rules
# Designed for clean, optimal production APKs on GitHub Releases

# -----------------------------------------------------------------------------
# 1. Native Methods & JNI
# -----------------------------------------------------------------------------
-keepclasseswithmembernames class * {
    native <methods>;
}

# -----------------------------------------------------------------------------
# 2. youtubedl-android (Python runtime, FFmpeg, Apache Commons, Jackson)
# -----------------------------------------------------------------------------
-keep class com.yausername.** { *; }
-keepclassmembers class com.yausername.** { *; }
-dontwarn com.yausername.**

-keep class org.apache.commons.** { *; }
-keepclassmembers class org.apache.commons.** { *; }
-dontwarn org.apache.commons.**

-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**

# -----------------------------------------------------------------------------
# 3. Media3 ExoPlayer & MediaSessionService
# -----------------------------------------------------------------------------
-keep class androidx.media3.session.** { *; }
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.common.** { *; }
-keep class com.amatsagu.reson.core.player.ResonPlaybackService { *; }
-dontwarn androidx.media3.**

# -----------------------------------------------------------------------------
# 4. Reson Domain Models (SQLite / Reactive State)
# -----------------------------------------------------------------------------
-keep class com.amatsagu.reson.model.** { *; }
-keepclassmembers class com.amatsagu.reson.model.** { *; }

# -----------------------------------------------------------------------------
# 5. WorkManager & Background Workers
# -----------------------------------------------------------------------------
-keep class androidx.work.** { *; }
-keep class com.amatsagu.reson.YtDlpUpdateWorker { *; }

# -----------------------------------------------------------------------------
# 6. Coil Image Loader
# -----------------------------------------------------------------------------
-keep class coil.** { *; }
-dontwarn coil.**

# -----------------------------------------------------------------------------
# 7. Kotlin Coroutines & Attributes
# -----------------------------------------------------------------------------
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn sun.misc.**
