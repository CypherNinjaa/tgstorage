# ProGuard / R8 rules for TgStorage
# Optimized for minimal APK size

# ─── Kotlin Serialization ───────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.tgstorage.**$$serializer { *; }
-keepclassmembers class com.tgstorage.** {
    *** Companion;
}
-keepclasseswithmembers class com.tgstorage.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ─── Room ───────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase { void <init>(); }
-keep @androidx.room.Entity class * { void <init>(); }
-keep @androidx.room.Dao interface *
-dontwarn androidx.room.paging.**

# ─── OkHttp (minimal keeps — let R8 strip unused) ──
-dontwarn okhttp3.**
-dontwarn okio.**
# Only keep the public suffix database needed at runtime
-keep class okhttp3.internal.publicsuffix.PublicSuffixDatabase { *; }

# ─── General size optimizations ────────────────────
-repackageclasses ''
-allowaccessmodification
-optimizationpasses 5
