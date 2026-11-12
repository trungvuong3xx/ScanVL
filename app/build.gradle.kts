plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
        buildConfig = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // CameraX cho giao diện camera quét tùy biến (Cân bằng điện thoại, Chụp đơn, Chụp hàng loạt)
    val cameraXVersion = "1.4.0"
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")

    // Google Play Services ML Kit Document Scanner (Chính thức từ Google)
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")

    // AndroidX Core & Lifecycle
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Jetpack Compose BOM & Material 3
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Coil: thumbnail tự giải mã đúng kích thước hiển thị
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Kéo-thả sắp xếp trang trong lưới
    implementation("sh.calvin.reorderable:reorderable:2.4.0")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Cài sẵn Baseline Profile của Compose khi sideload APK -> mở app & cuộn mượt ngay lần đầu
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // Unit test: kiểm chứng PDF hợp lệ + tốc độ
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.apache.pdfbox:pdfbox:2.0.32")

    // Debugging Compose Tooling
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
