plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Public settings, passed as -Pdekoi.<name>=... (CI takes them from repository
// variables). The publishable key is the anon role and is guarded by RLS; the
// Firebase values identify the app to FCM and are public by design.
// Debug builds may leave them empty (push is then off); release builds refuse to.
val settings =
    listOf(
        "supabaseUrl",
        "supabaseKey",
        "googleWebClientId",
        "firebaseProjectId",
        "firebaseAppId",
        "firebaseApiKey",
        "firebaseSenderId",
    ).associateWith { providers.gradleProperty("dekoi.$it").getOrElse("") }

android {
    namespace = "no.cyberfish.dekoi"
    compileSdk = 37

    defaultConfig {
        applicationId = "no.cyberfish.dekoi"
        minSdk = 29
        targetSdk = 37
        versionCode = providers.gradleProperty("dekoi.versionCode").getOrElse("1").toInt()
        versionName = providers.gradleProperty("dekoi.version").getOrElse("0.0.0-dev")
        buildConfigField("String", "SUPABASE_URL", "\"${settings.getValue("supabaseUrl")}\"")
        buildConfigField("String", "SUPABASE_KEY", "\"${settings.getValue("supabaseKey")}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${settings.getValue("googleWebClientId")}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${settings.getValue("firebaseProjectId")}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${settings.getValue("firebaseAppId")}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${settings.getValue("firebaseApiKey")}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"${settings.getValue("firebaseSenderId")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

// The alarm sound (ADR 0025) is the owner's file in SFX/, copied at build
// time so that there is one copy of it. The Variant API registers the copy
// as a generated resource directory of each variant and runs the task before
// resources merge; a plain source directory would not carry that dependency.
abstract class AlarmSound : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sound: RegularFileProperty

    @get:OutputDirectory
    abstract val res: DirectoryProperty

    @TaskAction
    fun copy() {
        sound.get().asFile.copyTo(res.file("raw/alarm_critical.mp3").get().asFile, overwrite = true)
    }
}

androidComponents.onVariants { variant ->
    val alarmSound =
        tasks.register<AlarmSound>("${variant.name}AlarmSound") {
            sound = rootDir.resolve("../SFX/CyberFish Error Sound.mp3")
        }
    variant.sources.res?.addGeneratedSourceDirectory(alarmSound, AlarmSound::res)
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    val missing = settings.filterValues { it.isBlank() }.keys
    doFirst {
        check(missing.isEmpty()) { "Release builds need -Pdekoi.<name> for: $missing" }
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(libs.glance.appwidget)
    constraints {
        implementation(libs.work.runtime) {
            because(
                "Glance 1.2.0 brings WorkManager 2.7.1 and Room 2.2.5, whose keep rules drop " +
                    "constructors used by reflection under R8 full mode: release builds crash at start",
            )
        }
    }

    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.serialization.json)

    implementation(libs.credentials)
    implementation(libs.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.code.scanner)
    implementation(libs.firebase.messaging)
    implementation(libs.libaums)
    implementation(libs.xz)

    testImplementation(libs.kotlin.test.junit4)
}
