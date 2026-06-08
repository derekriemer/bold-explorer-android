plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.compose.compiler)
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
    }

    buildFeatures {
        compose = true
        buildConfig = true
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

// SQLDelight 2.x doesn't wire its codegen into the KSP task automatically.
// Add the generated source directory to the main source set so Hilt's KSP
// can resolve BoldExplorerDatabase. The explicit dependsOn ensures ordering.
android.sourceSets.getByName("main") {
    java.srcDir("build/generated/sqldelight/code/BoldExplorerDatabase/debug")
}

afterEvaluate {
    listOf("Debug", "Beta", "Release").forEach { variant ->
        tasks.findByName("ksp${variant}Kotlin")
            ?.dependsOn("generate${variant}BoldExplorerDatabaseInterface")
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

    // Location
    implementation(libs.play.services.location)

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
}
