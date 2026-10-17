plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rotemaster.thaigame"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rotemaster.thaigame"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.4"
        ndk { abiFilters += listOf("arm64-v8a") }   // Retroid Pocket 5 / modern Android handhelds
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
}
