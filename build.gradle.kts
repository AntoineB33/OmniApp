plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    // On the classpath for :androidApp, but applied only when androidApp/google-services.json exists (the
    // pause-cue FCM push is optional — see docs/PAUSE_CUE_DELIVERY.md).
    alias(libs.plugins.googleServices) apply false
}

allprojects {
    tasks.configureEach {
        val isMachineSpecificCheckTask = name == "kotlinStoreYarnLock" ||
            name == "kotlinWasmStoreYarnLock" ||
            name == "kotlinUpgradeYarnLock" ||
            name == "verifyCommonMainSchedulerDatabaseMigration" ||
            name == "wasmJsBrowserTest" ||
            name == "wasmJsTest" ||
            name == "jsBrowserTest" ||
            name == "jsTest" ||
            name == "allTests"

        val isAppleTargetCheckTask = name.startsWith("compileKotlinIos") ||
            name.startsWith("compileTestKotlinIos") ||
            name.startsWith("linkKotlinIos") ||
            name.startsWith("compileKotlinApple") ||
            name.startsWith("compileTestKotlinApple") ||
            name.startsWith("linkKotlinApple") ||
            name.startsWith("ios") ||
            name.startsWith("apple")

        if (isMachineSpecificCheckTask || isAppleTargetCheckTask) {
            enabled = false
        }
    }
}