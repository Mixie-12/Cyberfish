import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// DeKoi Setup: prepares the SD card through Raspberry Pi Imager.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        allWarningsAsErrors = true
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.serialization.json)
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.zxing.core)

    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "no.cyberfish.dekoi.burner.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            // What :burner:suggestRuntimeModules reports beyond the defaults.
            modules("java.instrument", "jdk.unsupported")
            packageName = "DeKoi Setup"
            // MSI needs MAJOR.MINOR.BUILD; the release job passes the tag.
            packageVersion = providers.gradleProperty("dekoi.version").getOrElse("0.0.1")
            vendor = "CyberFish"
            windows {
                // Fixed forever: lets a newer MSI replace an installed one.
                upgradeUuid = "6f1d2b8e-4c7a-4f0e-9a51-2d3c8b7e5a10"
                menu = true
                shortcut = true
            }
        }
    }
}
