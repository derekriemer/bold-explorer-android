plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    jvm() // JVM target for running commonTest locally (fast, no emulator)
    androidTarget {
        compilations.all {
            kotlinOptions { jvmTarget = "17" }
        }
    }

    // iOS targets — produces BoldExplorerShared.xcframework on macOS.
    // Skipped automatically on Linux/CI without Xcode.
    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
        target.binaries.framework {
            baseName = "BoldExplorerShared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        // JVM-only: the offline replay harness (see navigation/replay). Not shipped in the app or
        // the iOS framework — it exists to sweep the S4 constants against a recorded walk.
        jvmMain.dependencies {
            implementation(libs.json)
        }
    }
}

android {
    namespace = "com.boldexplorer.shared"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Offline replay of a recorded walk against candidate matcher constants. See
// shared/src/jvmMain/kotlin/com/boldexplorer/shared/navigation/replay/ReplayCli.kt.
//
//   ./gradlew :shared:runReplay --args="<trail.gpx> <audio_log.jsonl> [--reverse] [--sweep]"
tasks.register<JavaExec>("runReplay") {
    group = "verification"
    description = "Replays a logged walk through ProgressTracker under candidate MatchTuning values."
    classpath =
        kotlin.jvm().compilations.getByName("main").let { compilation ->
            compilation.output.allOutputs + compilation.runtimeDependencyFiles
        }
    mainClass.set("com.boldexplorer.shared.navigation.replay.ReplayCliKt")
}
