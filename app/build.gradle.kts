import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Version lives in gradle.properties so tools/release.sh can bump it with sed.
val appVersionCode = providers.gradleProperty("idanplusil.versionCode").get().toInt()
val appVersionName = providers.gradleProperty("idanplusil.versionName").get()

// The self-update manifest. Override for testing against a branch:
//   -PupdateManifestUrl=https://raw.githubusercontent.com/<user>/IdanPlusIL/<branch>/config/update.json
val updateManifestUrl = providers.gradleProperty("updateManifestUrl")
    .orElse("https://raw.githubusercontent.com/davidturchak/IdanPlusIL/main/config/update.json")
    .get()

// keystore.properties is gitignored (see keystore.properties.example). Reading it
// at configuration time is configuration-cache safe: Gradle tracks the file as an
// input, so creating or removing it invalidates the cached configuration.
val keystoreProps: Properties? = rootProject.file("keystore.properties")
    .takeIf { it.isFile }
    ?.let { f -> Properties().apply { f.inputStream().use(::load) } }

android {
    namespace = "com.idanplusil.tv"
    // android-35 is the only installed platform, and it is also the ceiling
    // implied by media3 1.9.4 / coil 3.4.0 (both declare minCompileSdk 35).
    compileSdk = 35

    defaultConfig {
        applicationId = "com.idanplusil.tv"
        // Both tv-material and media3 declare 23. The API-31 target device sits
        // far above it, so this costs nothing and covers every Android TV 6.0+ box.
        minSdk = 23
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField(
            "String",
            "CHANNELS_CONFIG_URL",
            "\"https://raw.githubusercontent.com/davidturchak/IdanPlusIL/main/config/channels.json\"",
        )
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$updateManifestUrl\"")
        // Deliberately NO localeFilters/resourceConfigurations: adding res/values-iw
        // later must not be silently stripped.
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Installed TVs only accept an update signed with the key of the
            // build they already have, so a release is signed with the real
            // keystore or not at all. Without keystore.properties the output is
            // app-release-unsigned.apk, which cannot be installed - never a
            // debug-signed APK that would silently break every TV's update path.
            signingConfig = if (keystoreProps != null) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "keystore.properties not found: the release APK will be UNSIGNED. " +
                        "See keystore.properties.example.",
                )
                null
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets["main"].kotlin.srcDir("src/main/kotlin")

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }

    lint {
        // English-only is a deliberate v1 decision, not an oversight.
        disable += "MissingTranslation"
        checkDependencies = true
    }
}

dependencies {
    implementation(project(":resolver"))

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.profileinstaller)

    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.animation)
    implementation(libs.tv.material)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.common)

    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.okhttp.logging)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
