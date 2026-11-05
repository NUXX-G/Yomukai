plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

// Yomukai's web server: the sources from :shared behind an HTTP API, plus accounts, guests and a media proxy.
kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.nelson.yomukai.server.MainKt")
    applicationDefaultJvmArgs = listOf("-XX:+UseSerialGC", "-Xss512k", "-XX:MaxRAMPercentage=75")
}

val ktor = "3.2.3"

dependencies {
    implementation(project(":shared"))
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-netty:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-gson:$ktor")
    implementation("io.ktor:ktor-server-compression:$ktor")
    implementation("io.ktor:ktor-server-status-pages:$ktor")
    implementation("io.ktor:ktor-server-caching-headers:$ktor")
    implementation("io.ktor:ktor-server-default-headers:$ktor")
    implementation("io.ktor:ktor-server-sse:$ktor")
    implementation(libs.okhttp)
    implementation(libs.okhttp.dnsoverhttps)
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    // Reads WebP pages, so oversized ones can be shrunk to the reader's screen like JPEG and PNG.
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.12.0")
    testImplementation(libs.junit)
    testImplementation("io.ktor:ktor-server-test-host:$ktor")
}
