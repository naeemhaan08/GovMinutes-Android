plugins { id("com.android.application") }

android {
    namespace = "com.rain.govminutes"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rain.govminutes"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.1.4"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = false
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    androidResources { noCompress += listOf("onnx", "txt", "model") }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.github.k2-fsa:sherpa-onnx:1.13.4")
}
