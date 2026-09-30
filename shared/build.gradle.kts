import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("SchedulerDatabase") {
            packageName.set("org.example.project.scheduler.persistence.db")
        }
    }
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    
    jvm()
    
    js {
        browser()
    }
    
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }
    
    androidLibrary {
       namespace = "org.example.project.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
    }
    
    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.sqldelight.androidDriver)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.androidx.core.ktx)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.serializationJson)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.sqldelight.runtime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.serialization.kotlinxJson)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.sqliteDriver)
            implementation(libs.ktor.client.cio)
            // PRD §15: desktop event-based session lock/unlock detection (Windows WTSRegisterSessionNotification).
            implementation(libs.jna.platform)
            // `docs/invariants/scheduler.md` § *The best score*: the desktop's own solver (OR-Tools SCIP), asked while a
            // fill's wall-time budget lasts. Only the Windows native library is shipped — the deployed desktop is
            // Windows; elsewhere the loader fails and the scheduler runs without a platform solver.
            implementation(libs.ortools.java.get().toString()) { exclude(group = "com.google.ortools") }
            implementation(libs.ortools.win32)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.serialization.kotlinxJson)
            // ADR 0009: Skia natives for this machine, so a test can RENDER a calendar element headlessly
            // (`ImageComposeScene`) and measure where its edges land between pixels. Test classpath only.
            implementation(compose.desktop.currentOs)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.nativeDriver)
            implementation(libs.ktor.client.darwin)
        }
        jsMain.dependencies {
            implementation(libs.wrappers.browser)
            implementation(libs.ktor.client.js)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}

tasks.whenTaskAdded {
    if (name == "kotlinWasmStoreYarnLock" ||
        name == "verifyCommonMainSchedulerDatabaseMigration") {
        enabled = false
    }
}
// `StartupOnRealDbTest`: the desktop's start-up, minus its window, on a COPY of a real database — headless, so it
// never takes the screen. Copies the release DB (or `-PstartupDb=<path to scheduler-state.db>`) into the build dir
// first; the release app's own files are only read. `docs/PERFORMANCE.md` § *Start-up on a real account*.
val startupCheck by tasks.registering(Test::class) {
    group = "verification"
    description = "Times the app's start-up on a copy of the release DB and fails if the UI thread is held > 5 s."
    val testCompilation = kotlin.jvm().compilations.getByName("test")
    testClassesDirs = testCompilation.output.classesDirs
    classpath = files(testCompilation.output.allOutputs, testCompilation.runtimeDependencyFiles)
    dependsOn("jvmTestClasses")
    filter { includeTestsMatching("org.example.project.StartupOnRealDbTest") }
    val source =
        File(
            providers.gradleProperty("startupDb").orNull
                ?: (System.getProperty("user.home") + "/.omniapp-release/scheduler-state.db"),
        )
    val copyDir = layout.buildDirectory.dir("startup-check").get().asFile
    systemProperty("omniapp.startupCheckDir", copyDir.absolutePath)
    systemProperty("omniapp.stateDir", copyDir.absolutePath)
    maxHeapSize = "4g"
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
    doFirst {
        require(source.isFile) { "no database at $source (pass -PstartupDb=<path>)" }
        copyDir.deleteRecursively()
        copyDir.mkdirs()
        // The DB and its WAL together: a running app may hold committed pages only in the WAL.
        for (suffix in listOf("", "-wal")) {
            val f = File(source.path + suffix)
            if (f.isFile) f.copyTo(File(copyDir, "scheduler-state.db$suffix"), overwrite = true)
        }
    }
}
