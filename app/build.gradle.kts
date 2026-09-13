plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.touchlock"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.touchlock"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            // 简单起见不开启代码混淆，直接产出可安装 release APK
            isMinifyEnabled = false
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

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 依赖最小化：仅使用平台 API + Kotlin stdlib（由 Kotlin 插件自动引入），不引入 AndroidX/Material，
    // 以避免版本冲突、最大化构建成功率。
}
