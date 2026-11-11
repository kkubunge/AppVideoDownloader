# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.aiutil.videodownloader.** {
    *** Companion;
}
-keepclasseswithmembers class com.aiutil.videodownloader.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# WebView JavaScript bridge
-keepclassmembers class com.aiutil.videodownloader.browser.VideoJsBridge {
    public *;
}
