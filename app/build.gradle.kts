plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.microphun"
    compileSdk = 35

    // Pinned so the CMake toolchain is reproducible. Must match an installed
    // NDK: `android sdk list "ndk*"`.
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.microphun"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // Drop 32-bit ABIs; every device that matters for a realtime
            // visualiser is arm64. x86_64 is kept for the emulator.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                cppFlags += listOf("-std=c++17", "-ffast-math")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            // Keep the native build unstripped so ndk-stack can symbolise crashes.
            isJniDebuggable = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        prefab = true
        buildConfig = true
    }

    testOptions {
        unitTests.all {
            // Host tests are meant to be read, not just counted.
            it.testLogging { showStandardStreams = true }
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.oboe)

    // The corpus and the chainer are plain Kotlin with no Android dependency,
    // so they are tested on the host: ./gradlew :app:testDebugUnitTest
    testImplementation(libs.junit)
}
