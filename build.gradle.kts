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
        versionCode = 3
        versionName = "0.3.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("key.jks")
            storePassword = "aichat123456"
            keyAlias = "aichat"
            keyPassword = "aichat123456"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
