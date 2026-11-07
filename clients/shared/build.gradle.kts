import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Plain Kotlin shared by the Android app and DeKoi Setup. No UI types: each
// app turns the values here into its own Compose types.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// What goes on a card comes from device/, the single source of truth: the
// cloud-init template and host files are bundled as resources, and the pinned
// image and installer bundle become constants, so neither app parses
// image.json or installer.json at run time.
val device: File = rootDir.resolve("../device")

val deviceFiles =
    tasks.register<Sync>("deviceFiles") {
        from(device) {
            include("cloud-init/user-data.yaml", "host/**")
        }
        into(layout.buildDirectory.dir("generated/device/device"))
    }

val pins =
    tasks.register("pins") {
        val image = device.resolve("image.json")
        val installer = device.resolve("installer.json")
        val output = layout.buildDirectory.dir("generated/pins")
        inputs.files(image, installer)
        outputs.dir(output)
        doLast {
            val json = listOf(image, installer).associateWith { JsonSlurper().parse(it) as Map<*, *> }

            // Checked here because the values become Kotlin source.
            fun text(
                file: File,
                key: String,
                pattern: Regex,
            ): String {
                val value = json.getValue(file)[key] as? String ?: error("${file.name}: $key is missing")
                check(pattern.matches(value)) { "${file.name}: $key is not in the expected form" }
                return value
            }

            fun size(
                file: File,
                key: String,
            ): Long = (json.getValue(file)[key] as? Number)?.toLong()?.takeIf { it > 0 } ?: error("${file.name}: $key is missing")
            val sha256 = Regex("[0-9a-f]{64}")
            // A subset of the OCI distribution-spec repository name, with its owner.
            val repository = Regex("[a-z0-9]+([._-][a-z0-9]+)*(/[a-z0-9]+([._-][a-z0-9]+)*)+")
            val source =
                """
                |// Generated from device/image.json and device/installer.json by shared/build.gradle.kts.
                |package no.cyberfish.dekoi.shared
                |
                |internal val pinnedImage =
                |    Image(
                |        url = "${text(image, "url", Regex("https://downloads\\.raspberrypi\\.com/[A-Za-z0-9._/-]+\\.img\\.xz"))}",
                |        downloadSha256 = "${text(image, "download_sha256", sha256)}",
                |        downloadSize = ${size(image, "download_size")}L,
                |        extractSha256 = "${text(image, "extract_sha256", sha256)}",
                |        extractSize = ${size(image, "extract_size")}L,
                |    )
                |
                |internal val pinnedInstaller =
                |    Installer(
                |        repository = "${text(installer, "repository", repository)}",
                |        sha256 = "${text(installer, "digest", Regex("sha256:$sha256")).removePrefix("sha256:")}",
                |        size = ${size(installer, "size")}L,
                |    )
                |
                """.trimMargin()
            output
                .get()
                .file("no/cyberfish/dekoi/shared/Pins.kt")
                .asFile
                .apply { parentFile.mkdirs() }
                .writeText(source)
        }
    }

sourceSets.main {
    resources.srcDir(deviceFiles.map { it.destinationDir.parentFile })
}

kotlin {
    sourceSets.main {
        kotlin.srcDir(pins)
    }
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        allWarningsAsErrors = true
    }
}

dependencies {
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Rendered samples for the cloud-init schema check in CI (check-cloud-init.sh).
    systemProperty(
        "dekoi.renderedDir",
        layout.buildDirectory
            .dir("rendered")
            .get()
            .asFile.path,
    )
}
