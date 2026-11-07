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

// The CyberFish fonts (ADR 0020) with their OFL-1.1 licence texts, taken from
// the brand guide's directory so that there is one copy of each font.
val brandFonts =
    tasks.register<Sync>("brandFonts") {
        val fonts = rootDir.resolve("../UX/UI")
        from(fonts.resolve("Michroma")) {
            include("Michroma-Regular.ttf", "OFL.txt")
            rename("OFL.txt", "Michroma-OFL.txt")
        }
        from(fonts.resolve("JetBrains_Mono/static")) {
            include("JetBrainsMono-Regular.ttf", "JetBrainsMono-Medium.ttf", "JetBrainsMono-ExtraBold.ttf")
        }
        from(fonts.resolve("JetBrains_Mono")) {
            include("OFL.txt")
            rename("OFL.txt", "JetBrainsMono-OFL.txt")
        }
        into(layout.buildDirectory.dir("generated/fonts/fonts"))
    }

sourceSets.main {
    resources.srcDir(brandFonts.map { it.destinationDir.parentFile })
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
    // The tests check the English text, whatever the machine's language.
    systemProperty("user.language", "en")
}

compose.desktop {
    application {
        mainClass = "no.cyberfish.dekoi.burner.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            // What :burner:suggestRuntimeModules reports beyond the defaults,
            // and the locale data for Bokmål country names, sorting and
            // numbers, without which the runtime knows only English (ADR 0025).
            modules("java.instrument", "jdk.unsupported", "jdk.localedata")
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
