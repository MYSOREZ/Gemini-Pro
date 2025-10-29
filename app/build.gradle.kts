plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("kotlin-kapt")
    alias(libs.plugins.hilt.android.plugin)
}

android {
    namespace = "com.rx.geminipro"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rx.geminipro"
        minSdk = 24
        targetSdk = 36
        versionCode = 188
        versionName = "1.6.6"

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

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
    }
}

kapt {
    correctErrorTypes = true
    useBuildCache = true
    arguments {
        arg("kapt.incremental.apt", "false")
        // Revert previous unsupported option and keep only supported ones
        arg("dagger.fastInit", "ENABLED")
        arg("dagger.hilt.shareTestComponents", "true")
        arg("dagger.hilt.disableModulesHaveInstallInCheck", "true")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.webkit)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.datastore.preferences)
    implementation(libs.datastore)

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation("com.squareup:javapoet:1.13.0")
}
