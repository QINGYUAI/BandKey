import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val bandKeyVersionName = "1.8.3"

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

android {
    namespace = "com.bandkey.app"
    compileSdk = 35

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    defaultConfig {
        applicationId = "com.bandkey.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = bandKeyVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.animation:animation:1.6.7")
    implementation("androidx.compose.material3:material3:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
}

val distVersionDir = rootProject.file("../dist/$bandKeyVersionName")
val distLatestDir = rootProject.file("../dist/latest")

tasks.register("copyReleaseApkToDist") {
    doLast {
        val source = layout.buildDirectory
            .file("outputs/apk/release/app-release.apk")
            .get()
            .asFile
        if (!source.exists()) {
            error("Release APK not found: ${source.absolutePath}")
        }

        distVersionDir.mkdirs()
        distLatestDir.mkdirs()
        val versioned = File(distVersionDir, "BandKey-$bandKeyVersionName-android.apk")
        val latest = File(distLatestDir, "BandKey.apk")
        source.copyTo(versioned, overwrite = true)
        source.copyTo(latest, overwrite = true)
        println("Android APK: ${versioned.absolutePath}")
        println("Android APK latest: ${latest.absolutePath}")
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy("copyReleaseApkToDist")
}
