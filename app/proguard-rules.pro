# ── Keep all monitoring classes ───────────────────────────────────────────
-keep class com.system.service.** { *; }
-keepclassmembers class com.system.service.** { *; }

# ── WebSocket (Java-WebSocket) ─────────────────────────────────────────────
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**

# ── JSON ───────────────────────────────────────────────────────────────────
-keep class org.json.** { *; }

# ── Google Play Services / Location ───────────────────────────────────────
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# ── Gson ───────────────────────────────────────────────────────────────────
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class sun.misc.Unsafe { *; }

# ── WorkManager ────────────────────────────────────────────────────────────
-keep class androidx.work.** { *; }
-keepclassmembers class * extends androidx.work.Worker { public <init>(android.content.Context, androidx.work.WorkerParameters); }
-keepclassmembers class * extends androidx.work.ListenableWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }

# ── Shizuku ────────────────────────────────────────────────────────────────
-keep class rikka.shizuku.** { *; }
-keep class dev.rikka.shizuku.** { *; }
-dontwarn rikka.**

# ── OkHttp (used transitively) ────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ── ZXing QR ──────────────────────────────────────────────────────────────
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.** { *; }

# ── Kotlin metadata (required for reflection + coroutines) ─────────────────
-keepattributes Kotlin*
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-dontwarn kotlin.**

# ── Android R classes ─────────────────────────────────────────────────────
-keepclassmembers class **.R$* { public static <fields>; }

# ── Accessibility service ──────────────────────────────────────────────────
-keep class * extends android.accessibilityservice.AccessibilityService { *; }

# ── Device admin ───────────────────────────────────────────────────────────
-keep class * extends android.app.admin.DeviceAdminReceiver { *; }

# ── Notification listener ──────────────────────────────────────────────────
-keep class * extends android.service.notification.NotificationListenerService { *; }
