import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * Release signing credentials.
 *
 * Two sources, neither committed: CI supplies environment variables from repo
 * secrets, local builds read `keystore.properties` at the project root. The
 * keystore and that file are gitignored — an app signing key is a permanent
 * identity, and publishing one lets anyone ship updates users' devices accept.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun signingValue(env: String, property: String): String? =
    System.getenv(env) ?: keystoreProperties.getProperty(property)

android {
    namespace = "com.opennovel.reader"
    // 36 is mandated by androidx.core 1.17 (pulled in by the IReader stack).
    // targetSdk stays at 34 so no runtime behaviour changes ship with this.
    compileSdk = 36

    defaultConfig {
        // User-facing app identity is Hibana (話). The code namespace stays
        // com.opennovel.reader to avoid a risky package move across the source tree.
        applicationId = "com.hibana.app"
        // 26 is the floor declared by io.github.ireaderorg:source-api-android.
        // Supporting IReader extensions costs Android 7.x (API 24-25) support.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        // The release workflow passes -PversionName=<tag without v>, so the
        // installed app reports exactly what was shipped. Falls back to the
        // literal for local builds.
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            val storePath = signingValue("SIGNING_STORE_FILE", "storeFile")
            if (storePath != null && file(storePath).exists()) {
                storeFile = file(storePath)
                storePassword = signingValue("SIGNING_STORE_PASSWORD", "storePassword")
                keyAlias = signingValue("SIGNING_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("SIGNING_KEY_PASSWORD", "keyPassword")
                // v2/v3 give faster verification and rotation support; v1 keeps
                // pre-Nougat installs working, which our minSdk 26 doesn't need
                // but costs nothing.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Only attach the config when a keystore was actually resolved, so a
            // clone without credentials still builds (unsigned) instead of failing.
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
        debug {
            // Debug keeps the default debug key so `assembleDebug` needs nothing.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    testImplementation(libs.junit)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlin.reflect)
    implementation(libs.androidx.preference)

    // IReader extension runtime: extensions link against these at load time.
    implementation(libs.ireader.source.api)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ksoup)
    implementation(libs.ksoup.network)
    implementation(libs.kotlinx.datetime)

    // LNReader JS plugin engine.
    implementation(libs.rhino)

    // On-device OCR: lets TTS read manga pages (images) aloud.
    implementation(libs.mlkit.text.latin)
    implementation(libs.mlkit.text.japanese)
    implementation(libs.mlkit.text.korean)
    implementation(libs.mlkit.text.chinese)
    implementation(libs.mlkit.translate)
    implementation(libs.androidx.work)
    implementation(libs.coil.compose)

    debugImplementation(libs.androidx.ui.tooling)
}
