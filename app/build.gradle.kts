plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.perso.minilauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "fr.perso.minilauncher"
        minSdk = 26
        targetSdk = 34
        // le numéro de version augmente à chaque compilation GitHub, pour que les mises à jour s'installent par-dessus
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1) + 100
        versionName = "1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    // clé de signature FIXE (dans le dépôt) : chaque APK est signé pareil, donc la mise à jour remplace l'appli
    // sans désinstaller et sans perdre les réglages
    signingConfigs {
        create("fixe") {
            storeFile = file("minilauncher.jks")
            storePassword = "minilauncher"
            keyAlias = "minilauncher"
            keyPassword = "minilauncher"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixe")
        }
        release {
            signingConfig = signingConfigs.getByName("fixe")
            isMinifyEnabled = false
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
