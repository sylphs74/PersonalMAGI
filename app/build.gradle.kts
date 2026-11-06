import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

// OpenCL (Adreno GPU) backend is built only when an OpenCL SDK dir is provided:
//   <dir>/include/CL/*.h and <dir>/lib/libOpenCL.so (link stub; the phone's own driver is used at runtime)
val openclSdk: String? = System.getenv("POCKETMAGI_OPENCL_SDK")?.takeIf { File(it, "lib/libOpenCL.so").isFile }

android {
    namespace = "jp.pocketmagi"
    compileSdk = 36
    ndkVersion = "29.0.13113456"

    defaultConfig {
        applicationId = "jp.personalmagi"
        minSdk = 31
        targetSdk = 36
        versionCode = runNumber
        versionName = "0.1.$runNumber"

        ndk { abiFilters += listOf("arm64-v8a") }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DBUILD_SHARED_LIBS=ON",
                    "-DLLAMA_BUILD_COMMON=ON",
                    "-DLLAMA_BUILD_MTMD=ON",
                    "-DMTMD_VIDEO=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_APP=OFF",
                    "-DLLAMA_OPENSSL=OFF",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_BACKEND_DL=ON",
                    "-DGGML_CPU_ALL_VARIANTS=ON",
                    "-DGGML_LLAMAFILE=OFF",
                )
                if (openclSdk != null) {
                    arguments += listOf(
                        "-DGGML_OPENCL=ON",
                        "-DGGML_OPENCL_USE_ADRENO_KERNELS=ON",
                        "-DGGML_OPENCL_EMBED_KERNELS=ON",
                        "-DOpenCL_INCLUDE_DIR=$openclSdk/include",
                        "-DOpenCL_LIBRARY=$openclSdk/lib/libOpenCL.so",
                    )
                }
            }
        }
    }

    // The release key is never in the repository: CI decodes it from repository secrets
    // (SIGNING_KEYSTORE_BASE64 and the passwords). Without them (forks, local builds) the APK is
    // signed with the local debug key instead.
    val releaseKeystore = System.getenv("SIGNING_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = releaseKeystore
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // never ship the link stub: the vendor libOpenCL.so must be used on the phone
            excludes += "**/libOpenCL.so"
        }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}
