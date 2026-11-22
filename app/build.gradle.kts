plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// v1.0.144: স্থায়ী debug সিগনেচার — CI প্রতিবার নতুন মেশিনে নতুন
// keystore বানিয়ে নিত বলে প্রতিটা বিল্ডের সিগনেচার আলাদা হতো এবং
// আপডেট ইনস্টল হতো না (আনইনস্টল = সব লগইন মোছা)। এখন keystore-এর
// base64 কপি repo-তে (ci/debug.keystore.b64) থাকে; এখানে decode করে
// সেই একই keystore দিয়ে সব বিল্ড সাইন হয় — আপডেট সরাসরি ইনস্টল হবে।
val fixedDebugKeystore: java.io.File = rootProject.file("build/fixed-debug.keystore")
run {
    val b64 = rootProject.file("ci/debug.keystore.b64")
    if (b64.exists() && !fixedDebugKeystore.exists()) {
        fixedDebugKeystore.parentFile.mkdirs()
        fixedDebugKeystore.writeBytes(
            java.util.Base64.getDecoder().decode(b64.readText().trim())
        )
    }
}

android {
    namespace = "com.example.cloner"
    compileSdk = 35

    signingConfigs {
        getByName("debug") {
            storeFile = fixedDebugKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.example.cloner"
        minSdk = 26
        // BlackBox ফর্ক ইচ্ছা করে targetSdk 28 রাখে — নতুন Android-এর
        // আচরণ পরিবর্তনে hook ভাঙা এড়াতে। ব্যক্তিগত ব্যবহারে ঠিক আছে।
        targetSdk = 28
        versionCode = 129
        versionName = "1.0.144 স্থায়ী-সাইন"
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
