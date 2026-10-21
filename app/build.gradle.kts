plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.ozyman.holomemflicker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ozyman.holomemflicker"
        minSdk = 30
        targetSdk = 34
        versionCode = runNumber
        versionName = "0.1.$runNumber"
    }

    signingConfigs {
        create("flick") {
            // Private keystore, only from CI secrets (KEYSTORE_B64 / KEYSTORE_PASS). Never committed.
            val ks = System.getenv("KEYSTORE_PATH")
            if (ks != null && ks.isNotEmpty()) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASS")
                keyAlias = System.getenv("KEYSTORE_ALIAS") ?: "release"
                keyPassword = System.getenv("KEYSTORE_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("flick")
        }
        debug {
            signingConfig = signingConfigs.getByName("flick")
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.systemProperty("screens.dir", rootProject.file("screens").absolutePath) }
        }
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")

    // Off-device screenshots (Robolectric native graphics), see ScreensTest
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
}
