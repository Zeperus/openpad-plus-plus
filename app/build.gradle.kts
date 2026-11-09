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
        versionCode = 10
        versionName = "0.1.0-alpha.10"
        testInstrumentationRunner = "io.github.zeperus.openpad.OpenPadTestRunner"
        // Each instrumented test gets a fresh process and cleared app data, so "cold start" tests are real.
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        // Test builds (debug, alpha) show measured editor timings in Settings; a final release does not.
        buildConfigField("boolean", "DIAGNOSTICS", "false")
    }

    // The Alpha line is signed with ONE stable certificate (the one of v0.1.0-alpha.1/2). Its keystore lives outside the repository
    // (~/.openpad-signing, see docs/storage.md "Signing"): when OPENPAD_KEYSTORE is set the debug and alpha builds use it, otherwise
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
            buildConfigField("boolean", "DIAGNOSTICS", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // What testers install since Alpha 10: optimized and shrunk like a release and not debuggable (a debuggable build runs
        // Compose several times slower and lets anyone with USB access read the notes), but still with the measured timings.
        create("alpha") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            signingConfig = signingConfigs.getByName(if (alphaKeystore != null) "alpha" else "debug")
            buildConfigField("boolean", "DIAGNOSTICS", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        animationsDisabled = true
        // Random/property tests run a fifth of their cases locally and the full count on CI (`-Popenpad.fullProperties=true`).
        unitTests.all { test ->
            test.systemProperty("openpad.fullProperties", providers.gradleProperty("openpad.fullProperties").getOrElse("false"))
        }
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
