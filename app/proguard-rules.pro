# R8 规则。禁止使用 -ignorewarnings 掩盖真实问题（docs/security.md 第 9 节）。

-keepattributes *Annotation*, InnerClasses, Signature

# kotlinx.serialization：保留生成的序列化器
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.starveil.**$$serializer { *; }
-keepclassmembers class com.starveil.** {
    *** Companion;
}

# --- TradPlus ---
# 聚合 SDK 通过反射实例化广告样式与 adapter，类名与成员名都不能被改写。
# --- TradPlus ---
-keep public class com.tradplus.** { *; }
-keep class com.tradplus.ads.** { *; }
-dontwarn com.tradplus.**
