-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations

# kotlinx.serialization
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** { static **$* *; }
-keepclassmembers class <2>$<3> { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.idanplusil.resolver.**$$serializer { *; }
-keepclassmembers class com.idanplusil.resolver.** { *** Companion; }
# The app module has its own @Serializable types (the self-update manifest).
-keep,includedescriptorclasses class com.idanplusil.tv.**$$serializer { *; }
-keepclassmembers class com.idanplusil.tv.** { *** Companion; }

# OkHttp optional TLS providers
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# jsoup
-dontwarn org.jsoup.**
