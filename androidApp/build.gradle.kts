import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(projects.shared)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)

    // PRD §15 / ARCHITECTURE.md §8 pause-end cue delivery. The dependency is always present (so the FCM
    // service / token code compiles); the google-services plugin below is what activates it, and is applied
    // only when a google-services.json is dropped in — until then FirebaseMessaging calls are guarded no-ops.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

// Apply the google-services plugin only when the config file exists, so a fresh checkout without a Firebase
// project still builds (the pause-cue push is then simply inert). See docs/PAUSE_CUE_DELIVERY.md.
if (rootProject.file("androidApp/google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// Release signing read from local.properties (gitignored) or env vars, both populated by
// scripts/android-deploy.bat. Absent on a fresh checkout, so `assembleRelease` then produces an
// unsigned APK (the deploy script is what generates the keystore and wires these in).
val signingProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(propKey: String, envKey: String): String? =
    signingProps.getProperty(propKey) ?: System.getenv(envKey)
val releaseStorePath = signingValue("omniapp.releaseKeystore", "OMNIAPP_RELEASE_KEYSTORE")

android {
    namespace = "org.example.project"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.example.project"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    signingConfigs {
        create("release") {
            if (releaseStorePath != null && file(releaseStorePath).exists()) {
                storeFile = file(releaseStorePath)
                storePassword = signingValue("omniapp.releaseStorePassword", "OMNIAPP_RELEASE_STORE_PASSWORD")
                keyAlias = signingValue("omniapp.releaseKeyAlias", "OMNIAPP_RELEASE_KEY_ALIAS") ?: "omniapp"
                keyPassword = signingValue("omniapp.releaseKeyPassword", "OMNIAPP_RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // Only attach the signing config when a keystore is actually configured, so a fresh checkout
            // without one still builds (unsigned) instead of failing configuration.
            if (releaseStorePath != null && file(releaseStorePath).exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}