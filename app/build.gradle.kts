plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
    id("com.google.dagger.hilt.android")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.antigravity.copilot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.antigravity.copilot"
        minSdk = 26
        targetSdk = 30
        versionCode = 128
        versionName = "2.24.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // BuildConfig.VERSION_NAME / VERSION_CODE come from versionName / versionCode above.

        ndk {
            abiFilters.add("armeabi-v7a")
        }

        kapt {
            arguments {
                arg("room.schemaLocation", "$projectDir/schemas")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ""
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/license.txt"
            excludes += "META-INF/notice.txt"
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.02.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    baselineProfile(project(":baselineprofile"))

    // Jetpack Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Dependency Injection (Hilt)
    implementation("com.google.dagger:hilt-android:2.57.1")
    kapt("com.google.dagger:hilt-compiler:2.57.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Room Database
    val roomVersion = "2.7.2"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // Jetpack DataStore (Preferences & Versioned Settings)
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Map Engine: MapLibre Android SDK 13.x, OpenGL ES variant (Mali-G31 / armeabi-v7a; avoid default Vulkan artifact)
    implementation("org.maplibre.gl:android-sdk-opengl:13.7.1")

    // WorkManager & OkHttp (Resumable downloads with Range header)
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Location (Fused Location + Fallback to native LocationManager)
    implementation("com.google.android.gms:play-services-location:21.1.0")

    // Coroutines & Utilities
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kapt {
    correctErrorTypes = true
}
