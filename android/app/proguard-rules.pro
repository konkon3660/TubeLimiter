# R8 rules for the release build (isMinifyEnabled / isShrinkResources).
#
# The two things that break silently under shrinking here are kotlinx.serialization
# (the generated $$serializer classes and the Companion.serializer() entry points are
# only reached reflectively, so R8 sees them as dead) and ktor's engine plumbing.
# Everything else — activities, services, receivers — is kept via the manifest.

# ---------------------------------------------------------------------------
# kotlinx.serialization
# ---------------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-dontnote kotlinx.serialization.**

# The serializer for a @Serializable class is a nested $$serializer object that
# nothing references by name.
-keep,includedescriptorclasses class com.tubelimiter.app.**$$serializer { *; }

# ...reached through the class's Companion, which must survive with it. This covers
# sync/RemoteModels.kt (RemoteSettings, RemoteStreak, RemoteAchievement,
# RemoteDailyUsageTotal) and any @Serializable model added later.
-keepclassmembers class com.tubelimiter.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.tubelimiter.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# @SerialName maps Kotlin properties onto Postgres column names; losing the field
# names would rename every column on the wire.
-keepclassmembers @kotlinx.serialization.Serializable class com.tubelimiter.app.** {
    <fields>;
}

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------------------------------------------------------------
# ktor / supabase-kt
# ---------------------------------------------------------------------------
-keepclassmembers class io.ktor.** { volatile <fields>; }
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**
-dontwarn org.slf4j.**

# OkHttp is the ktor engine; these are its documented optional-dependency warnings.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------------------------------------------------------------------------
# Misc
# ---------------------------------------------------------------------------
# Keep line numbers so a Play Console stack trace stays readable after deobfuscation.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
