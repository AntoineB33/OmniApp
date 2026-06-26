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

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
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