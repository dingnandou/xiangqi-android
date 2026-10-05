plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.yijin.xiangqi.light"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.yijin.xiangqi.light"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "2.3"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    sourceSets.getByName("main").assets.srcDir("../engine/src/main/assets")
    androidResources { noCompress += "nnue" }
}

// 简单/普通使用 Java 对手；困难/大师调用独立且完整注册回调的 Pikafish 桥接。
