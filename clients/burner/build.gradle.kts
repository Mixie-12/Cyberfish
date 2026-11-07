import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// DeKoi Setup: prepares the SD card through Raspberry Pi Imager.
plugins {
    alias(libs.plugins.kotlin.jvm)
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

// The card's contents come from device/, bundled as resources at build time so
// that there is one copy of every host file.
val deviceFiles =
    tasks.register<Sync>("deviceFiles") {
        from(rootDir.resolve("../device")) {
            include("image.json", "cloud-init/user-data.yaml", "host/**")
        }
        into(layout.buildDirectory.dir("generated/device/device"))
    }

sourceSets.main {
    resources.srcDir(deviceFiles.map { it.destinationDir.parentFile })
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.serialization.json)

    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Rendered samples for the cloud-init schema check in CI.
    systemProperty(
        "dekoi.renderedDir",
        layout.buildDirectory
            .dir("rendered")
            .get()
            .asFile.path,
    )
}
