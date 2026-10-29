import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Release signing: read keystore.properties (repo root, gitignored) when present,
// otherwise fall back to the debug signing config so ad-hoc builds stay installable.
val keystorePropsFile = rootProject.file("keystore.properties")
val useReleaseSigning = keystorePropsFile.exists()
if (useReleaseSigning) {
    println("ZygiskRewire signing: using release keystore from ${keystorePropsFile.absolutePath}")
} else {
    println("ZygiskRewire signing: keystore.properties not found, release build falls back to the debug signing config")
}

android {
    namespace = "tech.thessemaj.zygiskrewire"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "tech.thessemaj.zygiskrewire"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (useReleaseSigning) {
            val props = Properties().apply {
                keystorePropsFile.inputStream().use { load(it) }
            }
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
            signingConfig = if (useReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}