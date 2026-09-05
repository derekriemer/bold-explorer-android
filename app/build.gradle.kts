plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.boldexplorer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.boldexplorer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // Burned in at compile time so the audio log can record which build produced it --
        // otherwise "was the fix actually installed for this walk" has no answer but memory.
        buildConfigField("long", "BUILD_TIMESTAMP_MS", "${System.currentTimeMillis()}L")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Orthogonal to buildTypes below (6 variants total: google/fossDebug/Beta/Release).
    // `google` is today's app unchanged (Fused + GNSS, switchable via the Debug screen).
    // `foss` has zero Google Play Services on its classpath — not just unused at runtime — for
    // F-Droid, which requires no proprietary SDK in the build path. See di/location source sets
    // under src/google/kotlin and src/foss/kotlin, and AGENTS.md's "Build variants" section.
    flavorDimensions += "distribution"
    productFlavors {
        create("google") {
            dimension = "distribution"
            // Inherits applicationId "com.boldexplorer" unchanged.
        }
        create("foss") {
            dimension = "distribution"
            // Distinct app ID: F-Droid always re-signs with its own key, so a shared ID couldn't
            // be installed alongside a Play/beta-signed build anyway (signature mismatch) — a
            // distinct ID costs nothing and enables side-by-side install for comparison testing.
            applicationIdSuffix = ".foss"
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "SHOW_DEBUG_FEATURES", "true")
        }
        register("beta") {
            initWith(getByName("release"))
            buildConfigField("boolean", "SHOW_DEBUG_FEATURES", "true")
            matchingFallbacks += "release"
        }
        release {
            buildConfigField("boolean", "SHOW_DEBUG_FEATURES", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

}

sqldelight {
    databases {
        create("BoldExplorerDatabase") {
            packageName.set("com.boldexplorer.db")
            schemaOutputDirectory.set(file("src/main/sqldelight/schema"))
        }
    }
}

// Custom a11y rule only (issue #4) — the built-in rule sets are intentionally left off since this
// repo has no prior detekt baseline; enabling them is a separate decision.
detekt {
    config.setFrom(rootProject.file("detekt.yml"))
    buildUponDefaultConfig = false
    disableDefaultRuleSets = true
    // The plain (non-variant) detekt task defaults to src/main/kotlin + src/test/kotlin only —
    // confirmed by dumping its configured `source` before this fix (62 files: 53 under src/main,
    // 9 under src/test, missing both flavor dirs entirely). Explicitly add the flavor source sets
    // so `make lint` also covers FusedLocationProviderImpl.kt (google) and both
    // LocationProviderRouter.kt variants — src/test/kotlin must stay listed too, or this becomes
    // a *narrower* source list than the default and silently drops test-source linting.
    source.setFrom("src/main/kotlin", "src/test/kotlin", "src/google/kotlin", "src/foss/kotlin")
}

// SQLDelight 2.x doesn't wire its codegen into the KSP task automatically. Each variant gets its
// own generated-code directory (build/generated/sqldelight/code/BoldExplorerDatabase/<variant>/)
// and must only see its OWN directory — adding every variant's directory to the shared "main"
// source set (tried first) caused duplicate-class compile errors, since "main" is merged into
// every variant's compilation and the generated classes have identical fully-qualified names
// across variants (same schema, just regenerated per variant). Per-variant wiring via the Variant
// API keeps each compile isolated and means this doesn't need updating again if a flavor or
// build type is ever added.
androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        variant.sources.java?.addStaticSourceDirectory(
            "build/generated/sqldelight/code/BoldExplorerDatabase/${variant.name}",
        )
        tasks.matching { it.name == "ksp${name}Kotlin" }.configureEach {
            dependsOn("generate${name}BoldExplorerDatabaseInterface")
        }
    }
}

dependencies {
    implementation(project(":shared"))

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Compose
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.navigation)
    implementation(libs.compose.hilt.navigation)

    // Lifecycle
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.process)

    // DataStore
    implementation(libs.datastore)
    implementation(libs.datastore.preferences)

    // Location — Fused (Google Play Services) is google-flavor only. The foss flavor is
    // GNSS-only via android.location.LocationManager (pure AOSP), so this dependency is
    // genuinely absent from that flavor's classpath, not merely unused at runtime — see
    // app/src/{google,foss}/kotlin/com/boldexplorer/location/LocationProviderRouter.kt.
    // String-invoke form: AGP creates googleImplementation/fossImplementation configurations
    // dynamically from the flavor names, so there's no compile-time DSL accessor for them.
    "googleImplementation"(libs.play.services.location)

    // SQLDelight
    implementation(libs.sqldelight.android.driver)
    implementation(libs.sqldelight.coroutines)

    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Unit tests (JVM — no device needed)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.sqldelight.sqlite.driver)
    // android.jar stubs org.json and every method throws; the real thing is needed to test the log codec.
    testImplementation(libs.json)

    // Custom detekt rules
    detektPlugins(project(":detekt-rules"))
}
