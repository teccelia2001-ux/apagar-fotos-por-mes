plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// No GitHub Actions o número da execução vira a versão do app.
val numeroBuild = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val arquivoChave: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "br.app.apagarfotos"
    compileSdk = 34

    defaultConfig {
        applicationId = "br.app.apagarfotos"
        minSdk = 26
        targetSdk = 34
        versionCode = numeroBuild
        versionName = "1.$numeroBuild"
    }

    signingConfigs {
        create("release") {
            if (arquivoChave != null) {
                storeFile = file(arquivoChave)
                storePassword = System.getenv("KEYSTORE_PASS")
                keyAlias = "apagarfotos"
                keyPassword = System.getenv("KEYSTORE_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (arquivoChave != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
}
