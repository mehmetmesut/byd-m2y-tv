import java.util.Properties

plugins {
    id("com.android.application")
}

// M2Y release key lives outside the repo; local.properties (gitignored) points to its properties file
val signingProps = Properties()
rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use { signingProps.load(it) }
signingProps.getProperty("m2y.signingProps")?.let { path -> File(path).reader().use { signingProps.load(it) } }

android {
    namespace = "com.m2y.tv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.m2y.tv"
        // single target: BYD DiLink 3.0 head unit, Android 10
        minSdk = 29
        targetSdk = 29
        versionCode = 125076801
        versionName = "1.25.07.68.1"
    }

    signingConfigs {
        if (signingProps.getProperty("m2y.storeFile") != null) create("m2y") {
            storeFile = file(signingProps.getProperty("m2y.storeFile"))
            storePassword = signingProps.getProperty("m2y.storePassword")
            keyAlias = signingProps.getProperty("m2y.keyAlias")
            keyPassword = signingProps.getProperty("m2y.keyPassword") ?: signingProps.getProperty("m2y.storePassword")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("m2y")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // no native code today; guards against a future dependency shipping other ABIs
            ndk { abiFilters += "arm64-v8a" }
        }
        debug {
            // emulator test build: BYD_SealU is x86_64 without ARM translation
            ndk { abiFilters += listOf("x86_64", "arm64-v8a") }
        }
    }

    androidResources {
        localeFilters += listOf("tr")
    }

    testOptions {
        // Turkish Windows locale breaks Robolectric ("wındows"); tests run in English
        unitTests.all { it.jvmArgs("-Duser.language=en", "-Duser.country=US") }
    }

    lint {
        // targetSdk 29 is intentional (single device, Android 10)
        disable += "ExpiredTargetSdkVersion"
        abortOnError = false
    }

    androidComponents.onVariants { variant ->
        variant.outputs.forEach { output ->
            if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                output.outputFileName = "M2YTV_${defaultConfig.versionName}-${variant.buildType}.apk"
            }
        }
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.zxing:core:3.5.3") // QR of the phone pairing page; pure Java, shrunk by R8

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
