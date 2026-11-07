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
// image becomes a constant, so neither app parses image.json at run time.
val device: File = rootDir.resolve("../device")

val deviceFiles =
    tasks.register<Sync>("deviceFiles") {
        from(device) {
            include("cloud-init/user-data.yaml", "host/**")
        }
        into(layout.buildDirectory.dir("generated/device/device"))
    }

val pinnedImage =
    tasks.register("pinnedImage") {
        val json = device.resolve("image.json")
        val output = layout.buildDirectory.dir("generated/image")
        inputs.file(json)
        outputs.dir(output)
        doLast {
            val image = JsonSlurper().parse(json) as Map<*, *>

            // Checked here because the values become Kotlin source.
            fun text(
                key: String,
                pattern: Regex,
            ): String {
                val value = image[key] as? String ?: error("image.json: $key is missing")
                check(pattern.matches(value)) { "image.json: $key is not in the expected form" }
                return value
            }

            fun size(key: String): Long = (image[key] as? Number)?.toLong()?.takeIf { it > 0 } ?: error("image.json: $key is missing")
            val sha256 = Regex("[0-9a-f]{64}")
            val source =
                """
                |// Generated from device/image.json by shared/build.gradle.kts.
                |package no.cyberfish.dekoi.shared
                |
                |internal val pinnedImage =
                |    Image(
                |        url = "${text("url", Regex("https://downloads\\.raspberrypi\\.com/[A-Za-z0-9._/-]+\\.img\\.xz"))}",
                |        downloadSha256 = "${text("download_sha256", sha256)}",
                |        downloadSize = ${size("download_size")}L,
                |        extractSha256 = "${text("extract_sha256", sha256)}",
                |        extractSize = ${size("extract_size")}L,
                |    )
                |
                """.trimMargin()
            output
                .get()
                .file("no/cyberfish/dekoi/shared/PinnedImage.kt")
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
        kotlin.srcDir(pinnedImage)
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
