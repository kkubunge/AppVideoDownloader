plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.aiutil.videodownloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aiutil.videodownloader"
        minSdk = 24
        targetSdk = 35
        // CI가 실행 번호를 넘겨준다. 올라가야 안드로이드가 '업데이트'로 취급한다.
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = "1.0.0"

        // CI가 -PbuildId=<커밋 해시>로 넘겨준다. 설치된 것이 어느 빌드인지
        // 앱 안에서 바로 확인할 수 있어야 "고쳤는데 그대로"를 가려낼 수 있다.
        val buildId = (project.findProperty("buildId") as String?)?.takeIf { it.isNotBlank() }
            ?: "local"
        buildConfigField("String", "BUILD_ID", "\"$buildId\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    debugImplementation("androidx.compose.ui:ui-tooling")
}
