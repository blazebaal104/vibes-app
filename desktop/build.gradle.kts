import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    kotlin("plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
}

val vibesVersion = (findProperty("vibesVersion") as String?) ?: "1.0.0"

// ---------------------------------------------------------------- shared sources
// The phone app's code is copied (not moved) into build/shared before compiling, so the Android
// project stays exactly as it is. Files that only make sense on Android are excluded here and have a
// desktop version with the same package/class names in src/main/kotlin.
val androidOnly = listOf(
    "VibesActivity.kt",                 // -> desktop/Main.kt
    "home/VibesHomeAdapter.kt",         // old RecyclerView home, unused by the Compose UI
    "theme/ThemePickerPreference.kt",   // Echo settings screen widget
    "karaoke/KaraokeService.kt",        // Android foreground service
    "karaoke/KaraokeHD.kt",             // MediaCodec decode/encode
    "notify/VibesNotifier.kt",          // Android notifications
    "ui/DeezerWebLogin.kt",             // Android WebView
    "update/Updater.kt",                // APK installer
)
val desktopOverrides = file("src/main/kotlin/dev/brahmkshatriya/echo/vibes").walkTopDown()
    .filter { it.isFile && it.extension == "kt" }
    .map { it.relativeTo(file("src/main/kotlin/dev/brahmkshatriya/echo/vibes")).invariantSeparatorsPath }
    .toSet()

val vibesCode = (findProperty("vibesCode") as String?)?.toIntOrNull() ?: 0
val genBuildConfig by tasks.registering {
    val out = layout.buildDirectory.file("generated/buildconfig/dev/brahmkshatriya/echo/BuildConfig.kt")
    inputs.property("code", vibesCode); inputs.property("name", vibesVersion)
    outputs.file(out)
    doLast {
        out.get().asFile.apply { parentFile.mkdirs() }.writeText(
            "package dev.brahmkshatriya.echo\n\nobject BuildConfig {\n" +
                "    const val VERSION_CODE = $vibesCode\n    const val VERSION_NAME = \"$vibesVersion\"\n" +
                "    const val BUILD_TYPE = \"windows\"\n    const val DEBUG = false\n}\n"
        )
    }
}

val syncShared by tasks.registering(Sync::class) {
    into(layout.buildDirectory.dir("shared"))
    from("../common/src/commonMain/kotlin")
    // the karaoke filter is shared with the phone's audio chain (Media3 AudioProcessor API, shimmed)
    from("../app/src/main/java") {
        include("dev/brahmkshatriya/echo/playback/renderer/KaraokeProcessor.kt")
        filter { line -> if ("androidx.annotation.OptIn" in line || "UnstableApi" in line) "" else line }
    }
    from("../deezer-extension/ext/src/main/java")
    from("../app/src/main/java") {
        include("dev/brahmkshatriya/echo/vibes/**")
        exclude(androidOnly.map { "dev/brahmkshatriya/echo/vibes/$it" })
        // a desktop file with the same path replaces the Android one
        exclude(desktopOverrides.map { "dev/brahmkshatriya/echo/vibes/$it" })
    }
}
val syncFonts by tasks.registering(Sync::class) {
    into(layout.buildDirectory.dir("sharedRes/fonts"))
    from("../app/src/main/res/font")
}

kotlin {
    jvmToolchain(21)
    sourceSets.main {
        kotlin.srcDir(syncShared)
        kotlin.srcDir(genBuildConfig.map { layout.buildDirectory.dir("generated/buildconfig").get() })
        resources.srcDir(syncFonts.map { layout.buildDirectory.dir("sharedRes").get() })
    }
    compilerOptions {
        freeCompilerArgs.addAll("-Xcontext-parameters", "-Xexpect-actual-classes", "-nowarn")
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.animation.ExperimentalAnimationApi",
            "androidx.compose.ui.ExperimentalComposeUiApi",
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
            "kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.animation)
    implementation(compose.foundation)
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.protobuf.java)
    implementation("io.coil-kt.coil3:coil-compose:${libs.versions.coil.get()}")
    implementation("io.coil-kt.coil3:coil-network-okhttp:${libs.versions.coil.get()}")
    implementation("dev.chrisbanes.haze:haze:1.7.2")
    // audio: MP3 decoding (Deezer 128/320) + FLAC
    implementation("javazoom:jlayer:1.0.1")
    implementation("org.jflac:jflac-codec:1.5.2")
    implementation("org.json:json:20240303") // Android ships org.json; the themes code uses it
    implementation("de.sfuhrm:jaad:0.8.7") // AAC / m4a (voice notes, uploads)
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "dev.brahmkshatriya.echo.vibes.desktop.MainKt"
        jvmArgs += listOf("-Xmx1g", "-Dsun.java2d.uiScale.enabled=true", "-Dfile.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "Vibes"
            packageVersion = vibesVersion
            description = "Vibes"
            vendor = "Vibes"
            modules("java.net.http", "java.desktop", "java.sql", "jdk.unsupported", "java.naming", "jdk.crypto.ec")
            windows {
                iconFile.set(project.file("icon.ico"))
                menu = true
                shortcut = true
                dirChooser = false
                perUserInstall = true
                upgradeUuid = "6f0c8c52-6e3b-4c9b-9a5e-6a9a3b1d7e21"
                menuGroup = "Vibes"
            }
        }
        buildTypes.release.proguard { isEnabled.set(false) }
    }
}
