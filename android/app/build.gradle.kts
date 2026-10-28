plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tech.mourid.museboard"
    compileSdk = 34

    defaultConfig {
        applicationId = "tech.mourid.museboard"
        minSdk = 26
        targetSdk = 34
        // Set in gradle.properties (shared with build-manual.sh).
        versionCode = (project.property("museboard.versionCode") as String).toInt()
        versionName = project.property("museboard.versionName") as String
    }

    signingConfigs {
        // Release keystore lives outside the repo (~/.muse-board-keystore/).
        // If it is absent the release build falls back to the debug key so
        // the APK stays sideloadable.
        create("release") {
            val props = project.findProperty("MUSEBOARD_STORE_FILE") as String?
            if (props != null) {
                storeFile = file(props)
                storePassword = project.findProperty("MUSEBOARD_STORE_PASSWORD") as String?
                keyAlias = project.findProperty("MUSEBOARD_KEY_ALIAS") as String?
                keyPassword = project.findProperty("MUSEBOARD_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if ((project.findProperty("MUSEBOARD_STORE_FILE") as String?) != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
    // Framework APIs only, plus the Kotlin stdlib and coroutines.
    // No AndroidX: the VM Gradle build is blocked (sandbox denies the
    // Gradle daemon's loopback TCP), so the app is built dependency-free
    // with build-manual.sh — see README. A plain `./gradlew
    // assembleRelease` on a normal machine builds the same code.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
