import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val malClientId: String = run {
    val props = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
    (props.getProperty("mal.clientId") ?: System.getenv("MAL_CLIENT_ID") ?: "").trim()
}

android {
    namespace = "com.nelson.yomukai"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.nelson.yomukai"
        minSdk = 26
        targetSdk = 37
        versionCode = 18
        versionName = "1.8.0"

        ndk {
            // tor ships ~7 MB per ABI; 32-bit x86 devices are not supported.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Optional MyAnimeList Client ID (https://myanimelist.net/apiconfig, type "android"), read from
        // local.properties as mal.clientId. Without it the app reads MAL's public data through Jikan.
        buildConfigField("String", "MAL_CLIENT_ID", "\"$malClientId\"")
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    sourceSets {
        // Exported Room schemas feed MigrationTestHelper.
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    // Exported schemas let Room migrations be reviewed and tested.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Sources, search, discovery and metadata shared with the web server
    implementation(project(":shared"))

    // Jetpack Compose (Material 3)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.profileinstaller)

    // Coil (Image Loading for Manga & Light Novels)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Jsoup (Web scraping for manga, novel and anime sites)
    implementation(libs.jsoup)
    implementation(libs.okhttp)
    implementation(libs.okhttp.dnsoverhttps)
    // TLS 1.3 on Android 8-9 (API 26-28): some sources (api.mangadex.org) refuse TLS 1.2
    implementation(libs.conscrypt.android)

    // Room Database (Local storage for library, history, bookmarks)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // AndroidX Media3 ExoPlayer (Anime video streaming with subtitles & quality selection)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource.okhttp)

    // Tor (Guardian Project): embedded tor daemon + WebView proxy override
    implementation(libs.tor.android)
    implementation(libs.androidx.webkit)

    // JSON Parsing
    implementation(libs.gson)

    // WorkManager (background library update checks)
    implementation(libs.androidx.work.runtime.ktx)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    // Room testing reads the exported schemas with this; the app itself does not use it.
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Classes from :shared are plain Kotlin: their data classes are immutable, so Compose may skip them.
composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("app/compose-stability.conf"))
}
