plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

// Yomukai's home worker: does the heavy jobs (voices, OCR, subtitles, downloads to Jellyfin...) for the VPS.
// It connects out to the VPS and asks for work; nothing at home is exposed.
kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.nelson.yomukai.worker.MainKt")
    applicationDefaultJvmArgs = listOf("-XX:+UseSerialGC", "-XX:MaxRAMPercentage=50")
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.okhttp)
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation(libs.junit)
    testImplementation(project(":server"))
    testImplementation("io.ktor:ktor-server-netty:3.2.3")
}
