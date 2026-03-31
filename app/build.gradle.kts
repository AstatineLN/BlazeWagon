plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // NOTE: Changed namespace to match the package name we are using
    namespace = "com.example.blazewagon"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.blazewagon"
        minSdk = 24 // Android 7.0, sufficient for modern Bluetooth/Location APIs
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    sourceSets.getByName("main") {
        res.srcDirs("src/main/res")
    }
}

dependencies {

    // 1. KOTLIN AND CORE
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")

    // 2. UI & LAYOUT
    // FIX: Reverting to a known stable ConstraintLayout version
    implementation("androidx.constraintlayout:constraintlayout:2.0.4")

    // 3. GOOGLE LOCATION SERVICES (For FusedLocationProviderClient)
    implementation("com.google.android.gms:play-services-location:21.0.1")

    // 4. TESTING (Standard dependencies)
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
