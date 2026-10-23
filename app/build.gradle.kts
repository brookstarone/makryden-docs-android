plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.makryden.docs"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.makryden.docs"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("MKDOCS_KEYSTORE_PATH")
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("MKDOCS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MKDOCS_KEY_ALIAS")
                keyPassword = System.getenv("MKDOCS_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // debuggable true is the default for debug
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
