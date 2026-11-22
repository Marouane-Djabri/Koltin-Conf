plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    android {
        namespace = "dev.filamentiso.shared"
        compileSdk = 37
        minSdk = 24
        withHostTest { }   // runs commonTest on the JVM: ./gradlew :shared:testAndroidHostTest
    }

    sourceSets {
        commonMain.dependencies {
            // Pure Kotlin + Compose UI only. No Filament here.
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidMain.dependencies {
            implementation(libs.filament.android)
            implementation(libs.gltfio.android)
            implementation(libs.lifecycle.runtime.compose)
        }
    }
}
