plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.cloner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.cloner"
        minSdk = 26
        // BlackBox ফর্ক ইচ্ছা করে targetSdk 28 রাখে — নতুন Android-এর
        // আচরণ পরিবর্তনে hook ভাঙা এড়াতে। ব্যক্তিগত ব্যবহারে ঠিক আছে।
        targetSdk = 28
        versionCode = 19
        versionName = "1.0.34 সব-চেকআপ"
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
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")

    // BlackBox ভার্চুয়াল ইঞ্জিন
    implementation(project(":blackbox:Bcore"))
}
