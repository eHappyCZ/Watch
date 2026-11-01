import java.util.Properties

plugins {
    id("com.android.application")
}

android {
    namespace = "cz.watch"
    compileSdk = 34
    defaultConfig {
        applicationId = "cz.watch"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        val serverUrl = providers.gradleProperty("serverUrl").getOrElse("wss://watch.example.com/ws")
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        create("release") {
            val props = Properties().apply {
                file("../keystore/keystore.properties").inputStream().use { load(it) }
            }
            storeFile = file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
