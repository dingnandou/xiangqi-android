plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// 引擎层：只负责「把 Pikafish 跑起来」与「把引擎状态翻译成 Kotlin 事件」。
// 按《02-技术方案与验收标准》§2，本模块不含任何页面与业务规则。
android {
    namespace = "com.yijin.xiangqi.engine"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                // 与 CMakeLists.txt 中锁定的架构一致：首版只出 arm64-v8a 真机包。
                // x86_64 仅在需要模拟器验证时临时追加，不要默认产出。
                arguments += listOf("-DANDROID_STL=c++_static")
                cppFlags += "-std=c++17"
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // NNUE 网络文件已经是 zstd 压缩，再套一层 deflate 基本压不动，
    // 强行压缩只会拖慢安装时解压并白白占用 CPU。
    androidResources {
        noCompress += "nnue"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}