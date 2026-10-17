plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

// One place decides the version; the code is worked out from it so the two never disagree.
val appVersionName = "0.1.1"
val appVersionCode = appVersionName.split(".").let { (major, minor, patch) -> major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt() }

/** What `local.properties` holds. Git ignores that file: it is where this machine's secrets live. */
val localProperties: Map<String, String> = rootProject.file("local.properties")
    .takeIf { it.exists() }
    ?.readLines()
    ?.mapNotNull { line ->
        val text = line.trim()
        if (text.startsWith("#") || "=" !in text) null else text.substringBefore("=").trim() to text.substringAfter("=").trim()
    }
    ?.toMap()
    .orEmpty()

fun buildSetting(name: String): String? = (localProperties[name] ?: System.getenv(name))?.takeIf(String::isNotBlank)

/** The key releases are signed with. Android refuses an update signed by a different key. */
val releaseKeystore = buildSetting("RACEWALL_KEYSTORE_FILE")?.let(rootProject::file)?.takeIf { it.exists() }
val releaseKeystorePassword = buildSetting("RACEWALL_KEYSTORE_PASSWORD")
val releaseKeyAlias = buildSetting("RACEWALL_KEY_ALIAS")

android {
    namespace = "com.racewall.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.racewall.android"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (releaseKeystore != null && releaseKeystorePassword != null && releaseKeyAlias != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = buildSetting("RACEWALL_KEY_PASSWORD") ?: releaseKeystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        // Robolectric reads the merged manifest and resources to stand up a context.
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.maxHeapSize = "3g" }
    }
}

dependencies {
    implementation(project(":core:bus"))
    implementation(project(":core:clock"))
    implementation(project(":core:topics"))
    implementation(project(":data:archive"))
    implementation(project(":data:account"))
    implementation(project(":data:openf1"))
    implementation(project(":data:jolpica"))
    implementation(project(":analysis"))
    implementation(project(":data:live"))
    implementation(project(":data:f1tv"))
    implementation(libs.media3.dash)
    implementation(libs.media3.ui)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.media3.exoplayer)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.rule)

    debugImplementation(libs.androidx.compose.ui.tooling)
    // Gives a Compose test the empty activity it renders into.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
