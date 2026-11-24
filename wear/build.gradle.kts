plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.cafeteria.wear"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // Debe ser IGUAL al del teléfono: la Data Layer de Wear OS solo entrega
        // mensajes entre apps con el mismo applicationId y la misma firma.
        applicationId = "com.example.cafeteria"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
}
