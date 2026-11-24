plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")
}

// Firebase se activa en cuanto pongas app/google-services.json (consola de Firebase).
// Sin ese archivo la app compila igual, solo que no recibe la notificación de "pedido listo".
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.example.cafeteria"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.cafeteria"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // URL de la API. Por defecto la de producción; la prueba E2E compila con
        // -PapiUrl=http://10.0.2.2:8080/api/ para usar el servidor de pruebas (backend/pruebas)
        val apiUrl = providers.gradleProperty("apiUrl").getOrElse("https://devnationqro.com/cafeteria-api/api/")
        buildConfigField("String", "API_URL", "\"$apiUrl\"")

        // Módulo Soporte: vacío = Firebase en la nube (normal). Para probar sin tocar la nube
        // con los emuladores de Firebase (firebase/): -PfirebaseEmulador=10.0.2.2
        val firebaseEmulador = providers.gradleProperty("firebaseEmulador").getOrElse("")
        buildConfigField("String", "FIREBASE_EMULADOR", "\"$firebaseEmulador\"")
    }

    testOptions {
        // Las pruebas de integración solo corren si se indica el servidor de pruebas:
        // gradlew :app:testDebugUnitTest -PapiPruebasUrl=http://localhost:8080/api/
        // Práctica 13: Robolectric (Room y SharedPreferences en la JVM, sin emulador) necesita los recursos
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("api.pruebas.url", providers.gradleProperty("apiPruebasUrl").getOrElse(""))
        }
    }

    buildTypes {
        debug {
            // Práctica 11 (White Box): cobertura JaCoCo de las pruebas unitarias
            // gradlew :app:createDebugUnitTestCoverageReport
            enableUnitTestCoverage = true
        }
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Room (SQLite local) para la práctica 4: usuarios
    implementation("androidx.room:room-runtime:2.8.3")
    implementation("androidx.room:room-ktx:2.8.3")
    ksp("androidx.room:room-compiler:2.8.3")
    // Mapa de OpenStreetMap para elegir la ubicación de entrega (no necesita API key)
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // Notificación "pedido listo": Firebase Cloud Messaging la trae al teléfono
    // y la API Wearable (Data Layer) la reenvía al reloj emparejado
    implementation(platform("com.google.firebase:firebase-bom:34.0.0"))
    implementation("com.google.firebase:firebase-messaging")
    // Módulo "Soporte" (prácticas 7, 9 y 10): cuentas con correo y contraseña y base
    // de datos compartida en la nube (Cloud Firestore) entre varios dispositivos
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    testImplementation(libs.junit)
    // org.json real (en las pruebas locales el de Android es solo un stub) y un servidor HTTP falso
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // Práctica 13: pruebas de persistencia (Room en memoria y SharedPreferences) en la JVM,
    // así corren en GitHub Actions sin necesidad de un emulador
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.6.1")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}