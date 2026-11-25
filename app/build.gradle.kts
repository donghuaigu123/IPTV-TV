plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.iptv.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.iptv.tv"
        // Android 4.4 (API 19) 起，覆盖绝大多数安卓 4.x 电视盒子
        minSdk = 19
        // 保持较低 targetSdk，兼容老设备的存储与权限行为
        targetSdk = 28
        versionCode = 3
        versionName = "1.2.0-tv"
        vectorDrawables.useSupportLibrary = true
        multiDexEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        // 让 Java 8 API 可在 Android 4.x 上运行
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation("androidx.multidex:multidex:2.0.1")

    // 全部降级到支持 API 19 的版本
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.4.2")
    implementation("com.google.android.material:material:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.2.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.5.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.5.1")
    implementation("androidx.activity:activity-ktx:1.5.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.preference:preference-ktx:1.2.0")
    implementation("androidx.drawerlayout:drawerlayout:1.1.1")

    implementation("com.squareup.okhttp3:okhttp:3.12.13")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    implementation("com.google.code.gson:gson:2.10.1")

    // ExoPlayer 2.x（最低支持 API 16，替代要求 API 21 的 Media3）
    val exo = "2.18.7"
    implementation("com.google.android.exoplayer:exoplayer-core:$exo")
    implementation("com.google.android.exoplayer:exoplayer-hls:$exo")
    implementation("com.google.android.exoplayer:exoplayer-dash:$exo")
    implementation("com.google.android.exoplayer:exoplayer-rtsp:$exo")
    implementation("com.google.android.exoplayer:exoplayer-smoothstreaming:$exo")
    implementation("com.google.android.exoplayer:exoplayer-ui:$exo")
}
