import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.1"
}

val appVersion = "2.4.0"
group = "com.noamp"
version = appVersion

kotlin { jvmToolchain(17) }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
}

compose.desktop {
    application {
        mainClass = "com.noamp.fx.MainKt"
        // Audio thread work is native; the JVM only drives the UI. Small heap, low-pause GC so the meters never stutter.
        jvmArgs += listOf("-Xms64m", "-Xmx512m", "-XX:+UseSerialGC", "-Dfile.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "NoAmpFX"
            packageVersion = appVersion
            description = "NoAmp FX - guitar amp & effects with Neural Amp Modeler"
            vendor = "NoAmp"
            includeAllModules = true
            // noamp.dll (built from /native) is copied to resources/windows-x64 before packaging and ships next to the app
            appResourcesRootDir.set(layout.projectDirectory.dir("resources"))
            windows {
                menuGroup = "NoAmp FX"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                iconFile.set(project.file("icons/noamp.ico"))
                upgradeUuid = "6d0f4c2e-8a31-4c55-9b7e-2f5a1d9c7e42"
            }
        }
    }
}
