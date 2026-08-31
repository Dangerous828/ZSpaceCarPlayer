# OkHttp
-keepclassmembers class okhttp3.internal.publicsuffix.PublicSuffixDatabase {
    private java.lang.ByteString publicSuffixListBytes;
    private java.lang.ByteString publicSuffixExceptionListBytes;
}

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }

# Glide
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep class com.bumptech.glide.generated.GeneratedAppGlideModuleImpl { *; }
