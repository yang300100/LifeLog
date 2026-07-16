# LifeLog Camera ProGuard Rules

# OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Room
-keep class com.lifelog.camera.data.local.entity.** { *; }

# Hilt / Dagger
-keep class dagger.hilt.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# JSON (org.json)
-keep class org.json.** { *; }

# NimBLE / BLE (no obfuscation needed for Android BT stack)
-keep class android.bluetooth.** { *; }

# Security Crypto / Tink — errorprone 注解仅编译时需要，运行时不存在
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.crypto.tink.**
-keep class com.google.crypto.tink.** { *; }
