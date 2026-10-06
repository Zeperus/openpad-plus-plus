plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.zeperus.openpad"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.zeperus.openpad"
        minSdk = 28
        targetSdk = 37
        versionCode = 3
        versionName = "0.1.0-alpha.3"
        testInstrumentationRunner = "io.github.zeperus.openpad.OpenPadTestRunner"
        // Each instrumented test gets a fresh process and cleared app data, so "cold start" tests are real.
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
    }

    // The Alpha line is signed with ONE stable certificate (the one of v0.1.0-alpha.1/2). Its keystore lives outside the repository
    // (~/.openpad-signing, see docs/storage.md "Signing"): when OPENPAD_KEYSTORE is set the debug build uses it, otherwise
    // Gradle falls back to its default debug key (fine for development and CI, but such APKs cannot update an Alpha install).
    val alphaKeystore: String? = providers.environmentVariable("OPENPAD_KEYSTORE").orNull
    if (alphaKeystore != null) {
        signingConfigs.create("alpha") {
            storeFile = file(alphaKeystore)
            storePassword = providers.environmentVariable("OPENPAD_KEYSTORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("OPENPAD_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("OPENPAD_KEY_PASSWORD").orNull
        }
    }

    buildTypes {
        debug {
            if (alphaKeystore != null) signingConfig = signingConfigs.getByName("alpha")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        animationsDisabled = true
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.commonmark)
    implementation(libs.commonmark.strikethrough)
    implementation(libs.commonmark.tasklist)
    implementation(libs.commonmark.tables)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestUtil(libs.androidx.test.orchestrator)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
