import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

// Release signing is opt-in: drop an untracked android/keystore.properties next to this
// module's parent with storeFile/storePassword/keyAlias/keyPassword and the release build
// is signed. Without it the build still succeeds, it just produces an unsigned APK, so CI
// and fresh clones never need the secret.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}
val configuredStoreFile = keystoreProperties.getProperty("storeFile").orEmpty()
val releaseStoreFile =
    if (configuredStoreFile.isBlank()) {
        null
    } else {
        rootProject.file(configuredStoreFile).takeIf { it.exists() }
    }

// Version lives in gradle.properties so a release bump is a one-line edit that does not
// touch the build script (and CI can override it with -PtubelimiterVersionCode=...).
val appVersionCode = (project.property("tubelimiterVersionCode") as String).trim().toInt()
val appVersionName = (project.property("tubelimiterVersionName") as String).trim()

android {
    namespace = "com.tubelimiter.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tubelimiter.app"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        // Names the runner class that executes src/androidTest on a device or emulator.
        // It lives in androidx.test:runner, which androidx.test.ext:junit does not pull in,
        // hence the separate dependency below.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.functions)
    implementation(libs.ktor.client.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumentation tests (src/androidTest): they need a real Android runtime, so they run
    // via `connectedDebugAndroidTest` on a device/emulator, not with the JVM unit tests.
    // No Espresso/Compose test rule here on purpose - the one suite that exists exercises the
    // DataStore layer, not the UI; see SettingsStoreRoundTripTest's header for why.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

ktlint {
    // The existing source is formatted in IntelliJ's Kotlin style, not ktlint's stricter
    // "ktlint_official" one; pinning the style keeps the check meaningful (unused imports,
    // import order, indentation, spacing) instead of demanding a wholesale reformat.
    // @Composable functions are PascalCase by convention, not a naming violation.
    additionalEditorconfig.set(
        mapOf(
            "ktlint_code_style" to "intellij_idea",
            "ktlint_function_naming_ignore_when_annotated_with" to "Composable,Preview",
            // Pure line-wrapping preference; the source deliberately wraps expression
            // bodies that ktlint would pull back onto the signature line.
            "ktlint_standard_function-signature" to "disabled",
        ),
    )
    // The backlog is cleared, so the check is enforcing: a violation fails the build.
    // `./gradlew ktlintFormat` fixes the auto-correctable ones.
    ignoreFailures.set(false)
}
