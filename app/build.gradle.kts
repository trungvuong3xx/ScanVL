plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Số build tăng dần trên GitHub Actions -> mỗi APK mới cài đè (update) lên bản cũ được
val ciBuildNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

// Thông tin keystore ký bản Release (lấy từ biến môi trường / GitHub Secrets)
val releaseKeystorePath: String? = System.getenv("KEYSTORE_PATH")
val hasReleaseKeystore: Boolean = releaseKeystorePath != null && file(releaseKeystorePath).exists()

android {
    namespace = "com.scanvl.app"
    compileSdk = 34

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = file(releaseKeystorePath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.scanvl.app"
        minSdk = 24
        targetSdk = 34
        versionCode = ciBuildNumber
        versionName = "1.0.$ciBuildNumber"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            // R8: thu gọn + tối ưu code -> APK nhẹ, chạy mượt hơn bản debug rất nhiều
            isMinifyEnabled = true
            isShrinkResources = true
            // Có keystore thì ký bằng keystore riêng, không có thì tạm ký bằng debug key để vẫn cài được
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Google Play Services ML Kit Document Scanner (Chính thức từ Google)
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")

    // AndroidX Core & Lifecycle
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.1")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Jetpack Compose BOM & Material 3
    val composeBom = platform("androidx.compose:compose-bom:2024.05.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Coil để hiển thị ảnh thumbnail trang đầu tiên của tài liệu
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Gson để lưu trữ metadata tài liệu Scoped Storage
    implementation("com.google.code.gson:gson:2.11.0")

    // Debugging Compose Tooling
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
