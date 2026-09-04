plugins {
    id("com.android.application")
}

android {
    namespace = "com.unlocksb.module"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.unlocksb.module"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // libxposed API（101）：由 LSPosed 框架提供，仅编译期引用，绝不打包进 APK
    compileOnly(files("libs/libxposed-api-101.0.1.jar"))
}
