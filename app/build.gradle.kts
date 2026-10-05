plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.yijin.xiangqi.light"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.yijin.xiangqi.light"
        minSdk = 23
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

// 简版使用纯 Java 本地走棋，不依赖实验中的原生引擎模块。
