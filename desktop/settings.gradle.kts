// Vibes for Windows (Compose Desktop). A separate Gradle build so it never needs the Android SDK:
// it compiles the SAME Vibes UI code as the phone app (../app/.../vibes), the shared :common models
// and the Deezer extension, plus desktop replacements for the few Android-only pieces (src/main/kotlin).
// Build: ./gradlew -p desktop run | packageExe
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "VibesDesktop"
