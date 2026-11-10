# R8 rules for AHVEN Downloader (release builds). If a release build crashes at start, the first suspect is here.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable

# yt-dlp wrapper library: reads JSON into its own classes by reflection (Jackson) and runs native binaries.
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class org.apache.commons.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.**
-dontwarn org.apache.**
-dontwarn javax.**
-dontwarn java.lang.invoke.**

# Room entities / enums stored by name
-keep class com.aven.downloader.data.** { *; }
-keepclassmembers enum * { public static **[] values(); public static ** valueOf(java.lang.String); }

# Kotlin coroutines / Compose are covered by their own consumer rules.

# Optional classes referenced by Jackson / commons-compress that Android does not have
-dontwarn java.beans.**
-dontwarn org.w3c.dom.bootstrap.**
-dontwarn org.slf4j.**
-dontwarn org.tukaani.**
-dontwarn com.github.luben.**
-dontwarn org.brotli.**
-dontwarn org.objectweb.asm.**
-dontwarn javax.annotation.**
