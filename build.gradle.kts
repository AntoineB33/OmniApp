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
// ---- Test your change (README § Test your change) ----------------------------------------------------------------
// Four commands, the same on Windows, macOS and Linux, so a change is checked and tried with no script to choose and
// no setting to know. None of them touches the live release app's data (`~/.omniapp-release`, CLAUDE.md): the
// desktop and web runs use their own throwaway state, and the phone install is never uninstalled.

val tryGroup = "test your change"
val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

/** The Android SDK, read where Android Studio and the README put it: `local.properties`, then the environment. */
val androidSdkDir: File? = run {
    val props = java.util.Properties()
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { props.load(it) }
    (props.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT"))
        ?.let(::File)?.takeIf { it.isDirectory }
}

tasks.register("checkChange") {
    group = tryGroup
    description = "Runs the tests and builds every app this machine can build — the one check before trying a change."
    dependsOn(
        ":shared:jvmTest",
        ":shared:compileKotlinJs",
        ":shared:compileKotlinWasmJs",
        ":shared:compileIosMainKotlinMetadata",
        ":desktopApp:compileKotlin",
        ":webApp:compileKotlinWasmJs",
        ":webApp:compileKotlinJs",
    )
    // The Android half needs the SDK; without it the check says so instead of failing on a missing install.
    if (androidSdkDir != null) dependsOn(":shared:compileAndroidMain", ":androidApp:compileDebugKotlin")
    // The start-up check times a launch on a COPY of the release database, so it only runs where there is one.
    val releaseDb = File(System.getProperty("user.home"), ".omniapp-release/scheduler-state.db")
    if (releaseDb.isFile) dependsOn(":shared:startupCheck")
    // Plain values only below: the configuration cache cannot carry the script's own objects into an action.
    val androidSkipped = androidSdkDir == null
    val startupSkipped = !releaseDb.isFile
    val releaseDbPath = releaseDb.path
    doLast {
        println()
        println("checkChange: green.")
        if (androidSkipped) println("  (Android skipped: no Android SDK — see README § Requirements.)")
        if (startupSkipped) println("  (Start-up check skipped: it needs a release database at $releaseDbPath.)")
        println("Now try it: ./gradlew tryDesktop | tryAndroid | tryWeb   (README § Test your change)")
    }
}

// The desktop app on a throwaway account, offline unless asked (`-Ponline`): see desktopApp/build.gradle.kts.
tasks.register("tryDesktop") {
    group = tryGroup
    description = "Opens the desktop app on a throwaway local account (~/.omniapp-try), offline unless -Ponline."
    dependsOn(":desktopApp:run")
}

tasks.register("tryWeb") {
    group = tryGroup
    description = "Serves the web app (Wasm) and opens it in your browser. Stop it with Ctrl+C."
    dependsOn(":webApp:wasmJsBrowserDevelopmentRun")
}

tasks.register<Exec>("tryAndroid") {
    group = tryGroup
    description = "Installs the debug app on the connected phone or emulator and opens it. Never uninstalls anything."
    dependsOn(":androidApp:installDebug")
    val adb = androidSdkDir?.let { File(it, "platform-tools/" + if (isWindows) "adb.exe" else "adb") }
        ?.takeIf { it.isFile }?.path ?: "adb"
    commandLine(adb, "shell", "am", "start", "-n", "org.example.project/.MainActivity")
    val hasSdk = androidSdkDir != null
    doFirst {
        require(hasSdk) {
            "tryAndroid needs the Android SDK: set sdk.dir in local.properties (README § Requirements)."
        }
    }
}
