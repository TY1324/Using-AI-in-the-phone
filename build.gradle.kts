plugins {
    id("com.android.application") version "8.2.2"
}

android {
    namespace = "com.example.aichat"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.aichat"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
