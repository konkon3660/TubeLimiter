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
# auth/SupabaseConfig.kt의 createSupabaseClient는 엔진을 명시하지 않으므로 ktor가 HttpClient()를
# 만들면서 엔진 구현을 **ServiceLoader로** 찾는다: ktor-client-core가
# META-INF/services/io.ktor.client.HttpClientEngineContainer를 읽고 거기 적힌 클래스를
# 리플렉션으로 인스턴스화한다(ktor-client-okhttp의 OkHttpEngineContainer).
#
# 이름으로 참조하는 코드가 어디에도 없기 때문에 R8은 이 컨테이너를 죽은 코드로 보고 지울 수
# 있고, 그러면 릴리스 APK에서 "Failed to find HTTP client engine implementation"으로 모든
# Supabase 호출이 실패한다. 디버그 빌드는 minify가 없어 절대 드러나지 않는 종류의 사고다.
# ServiceLoader가 no-arg 생성자로 만들기 때문에 클래스만이 아니라 멤버까지 남겨야 한다.
-keep class io.ktor.client.HttpClientEngineContainer
-keep class * implements io.ktor.client.HttpClientEngineContainer { *; }

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
