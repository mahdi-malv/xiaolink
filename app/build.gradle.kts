import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "wtf.mahdi.glassesconnect"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "wtf.mahdi.glassesconnect"
        minSdk = 24
        targetSdk = 37
        // CI sets these from the git tag and run number (see .github/workflows/release.yml).
        versionCode = providers.environmentVariable("VERSION_CODE").orNull?.toInt() ?: 1
        versionName = providers.environmentVariable("VERSION_NAME").orNull ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Gemini Live API key (Google AI Studio) and model, from local.properties.
        buildConfigField("String", "GEMINI_API_KEY", "\"${localProps.getProperty("gemini.apiKey", "")}\"")
        buildConfigField("String", "GEMINI_MODEL", "\"${localProps.getProperty("gemini.model", "gemini-3.8-live")}\"")
        // Model that answers take_photo questions (one generateContent call per photo).
        buildConfigField("String", "GEMINI_VISION_MODEL", "\"${localProps.getProperty("gemini.visionModel", "gemini-flash-lite-latest")}\"")
    }

    // Release signing: env (CI secrets) first, then local.properties (release.storeFile,
    // release.storePassword, release.keyAlias, release.keyPassword). storeFile is relative to
    // the project root. Without a keystore the release APK is unsigned.
    val signing = { envName: String, prop: String ->
        providers.environmentVariable(envName).orNull ?: localProps.getProperty(prop)
    }
    signingConfigs {
        signing("RELEASE_KEYSTORE", "release.storeFile")?.let { path ->
            val storePass = signing("RELEASE_KEYSTORE_PASSWORD", "release.storePassword")
            create("release") {
                storeFile = rootProject.file(path)
                storePassword = storePass
                keyAlias = signing("RELEASE_KEY_ALIAS", "release.keyAlias") ?: "xiaolink"
                // PKCS12: key password is the store password unless set.
                keyPassword = signing("RELEASE_KEY_PASSWORD", "release.keyPassword") ?: storePass
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // Never ship a developer's key: release users enter their own in onboarding or More.
            buildConfigField("String", "GEMINI_API_KEY", "\"\"")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
