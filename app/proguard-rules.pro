# Keep WebSocket classes
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**

# Keep JSON classes
-keep class org.json.** { *; }

# Keep app classes
-keep class com.system.service.** { *; }

# Keep Google Play Services
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**
