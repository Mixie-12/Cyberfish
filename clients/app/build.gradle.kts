import groovy.json.JsonSlurper

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

// The decoy's name on the network, from the canary image's persona (ADR 0018),
// so the app names what the owner sees on a computer. Checked here because it
// becomes Java source: a NetBIOS name is at most 15 characters.
val decoyName: String =
    (JsonSlurper().parse(rootDir.resolve("../device/canary/persona/persona.json")) as Map<*, *>)["name"]
        .let { it as? String ?: error("persona.json: name is missing") }
        .also { check(Regex("[a-z0-9-]{1,15}").matches(it)) { "persona.json: name is not a NetBIOS name" } }

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
        buildConfigField("String", "DECOY_NAME", "\"$decoyName\"")
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
