plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "dev.filamentiso.shared"
        compileSdk = 37
        minSdk = 24
        withHostTest { }   // runs commonTest on the JVM: ./gradlew :shared:testAndroidHostTest
        androidResources { enable = true } // ships composeResources (map style) in the APK
    }

    // Kotlin/JS (not Wasm): MapLibre Compose has no Wasm artifact yet.
    js(IR) {
        browser {
            testTask { enabled = false } // commonTest runs on the JVM (testAndroidHostTest); no headless browser needed
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Pure Kotlin + Compose + MapLibre Compose. No Filament or three.js here.
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.maplibre.compose)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.filekit.dialogs.compose) // file picker: system picker on Android, <input type=file> on the Web
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jsMain.dependencies {
            implementation(npm("three", libs.versions.three.get()))
        }
        androidMain.dependencies {
            implementation(libs.maplibre.native.runtime.opengl.android) // the native map engine (libmaplibre-native-c.so)
            implementation(libs.filament.android)
            implementation(libs.gltfio.android)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.androidx.activity.compose)
        }
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "dev.filamentiso.resources"
}
