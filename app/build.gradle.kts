import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// sherpa-onnx (Apache-2.0): natural voices on the phone, without a connection. Kept on 1.x: 2.x dropped the built-in
// espeak-ng phonemizer that Piper voices need (tested: 2.0.1 makes empty audio). Its official AAR is not on Maven
// Central, so it is fetched from the project's GitHub release once, checked against this SHA-256, and kept out of git.
val sherpaVersion = "1.13.8"
val sherpaSha256 = "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471"
val sherpaAar = rootProject.file(".android-libs/sherpa-onnx-static-$sherpaVersion.aar")
val fetchSherpa by tasks.registering {
    // Plain values only: the configuration cache cannot keep references to this script.
    val target = sherpaAar
    val expected = sherpaSha256
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-static-link-onnxruntime-$sherpaVersion.aar"
    outputs.file(target)
    doLast {
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        if (target.isFile && sha(target) == expected) return@doLast
        target.parentFile.mkdirs()
        val part = File(target.path + ".part")
        // GitHub's downloads sometimes answer 5xx for a moment: a few spaced retries before giving up.
        var attempt = 0
        while (true) {
            val ok = runCatching { URI(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } } }
            if (ok.isSuccess) break
            if (++attempt >= 3) throw ok.exceptionOrNull()!!
            Thread.sleep(attempt * 15_000L)
        }
        check(sha(part) == expected) { "sherpa-onnx: SHA-256 incorrecto" }
        part.renameTo(target)
    }
}
tasks.named("preBuild") { dependsOn(fetchSherpa) }

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
        versionCode = 33
        versionName = "2.7.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Optional MyAnimeList Client ID (https://myanimelist.net/apiconfig, type "android"), read from
        // local.properties as mal.clientId. Without it the app reads MAL's public data through Jikan.
        buildConfigField("String", "MAL_CLIENT_ID", "\"$malClientId\"")
    }

    buildTypes {
        release {
            // ndk.abiFilters cannot be set together with ABI splits: the processors are listed in splits below.
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    // One APK per processor for releases (much smaller to download) plus a universal one; debug builds stay one APK.
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
    packaging {
        jniLibs {
            // Native libraries compressed inside the APK (a much smaller download; Android unpacks them at install).
            useLegacyPackaging = true
            // 32-bit x86 phones are not supported (Tor has no build for them).
            excludes += "lib/x86/**"
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
    // Bubble OCR and translation on the device (ML Kit): recognition through Google Play services (no models in the
    // APK); translation models download once per language, only when the user turns translation on.
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-japanese:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation(files(sherpaAar))
    // Voice packs come as .tar.bz2 from sherpa-onnx's releases.
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

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
    // room-testing reads schemas with a newer kotlinx-serialization than the app otherwise brings in;
    // without this the test APK fails with AbstractMethodError.
    constraints { implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.9.0") }
    // Room testing reads the exported schemas with this; the app itself does not use it.
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Classes from :shared are plain Kotlin: their data classes are immutable, so Compose may skip them.
composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("app/compose-stability.conf"))
}
