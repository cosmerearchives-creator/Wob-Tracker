plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.cosmerearchives.wobtracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cosmerearchives.wobtracker"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("releaseVersionCode").orNull?.toIntOrNull() ?: 1
        versionName = providers.gradleProperty("releaseVersionName").orNull ?: "0.1.0-alpha"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val keystoreFile = System.getenv("ANDROID_KEYSTORE_FILE")
            if (!keystoreFile.isNullOrBlank()) {
                signingConfig = signingConfigs.create("ciRelease") {
                    storeFile = file(keystoreFile)
                    storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                    keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
                }
            }
        }
    }

    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
