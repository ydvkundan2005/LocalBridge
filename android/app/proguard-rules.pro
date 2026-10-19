# LocalBridge ProGuard rules
# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.** { *; }
# Keep Shizuku API
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class dev.localbridge.android.**$$serializer { *; }
-keepclassmembers class dev.localbridge.android.** { *** Companion; }
-keepclasseswithmembers class dev.localbridge.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}
