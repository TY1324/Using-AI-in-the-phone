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
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

repositories {
    google()
    mavenCentral()
    maven { url = uri("https://maven.aliyun.com/repository/central") }
}
