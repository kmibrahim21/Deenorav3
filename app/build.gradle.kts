import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.kmibrahim.deenorav3"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.deenora"
        minSdk = 24
        targetSdk = 36
        versionCode = 7
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // keystore.properties ফাইল থেকে ডাটা পড়ার লজিক
        val keystorePropertiesFile = rootProject.file("keystore.properties")
        val keystoreProperties = Properties()
        if (keystorePropertiesFile.exists()) {
            keystoreProperties.load(FileInputStream(keystorePropertiesFile))
        }

        create("release") {
            // যদি প্রপার্টি ফাইলে ভ্যালু থাকে তবে সেটি নিবে, নাহলে আপনার দেওয়া ডিফল্ট পাথ/পাসওয়ার্ড ব্যবহার করবে
            storeFile = if (keystoreProperties.containsKey("storeFile")) {
                file(keystoreProperties["storeFile"] as String)
            } else {
                file("E:\\DEENORA3\\Deenora")
            }

            storePassword = (keystoreProperties["storePassword"] as? String) ?: "269596"
            keyAlias = (keystoreProperties["keyAlias"] as? String) ?: "km"
            keyPassword = (keystoreProperties["keyPassword"] as? String) ?: "269596"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // This fixes the "App Bundle contains native code, and you've not uploaded debug symbols" warning
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
        debug {
            // Debug মোডেও একই সাইনিং ব্যবহার করা হচ্ছে যাতে গুগল সাইন-ইন কাজ করে
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
