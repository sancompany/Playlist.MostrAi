plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "br.com.mostrai.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.mostrai.player"
        minSdk = 26
        targetSdk = 26
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    // Testes unitários rodam no JVM puro, sem o Android real: o android.jar de
    // teste "stuba" org.json.* para lançar exceção em vez de parsear de
    // verdade. A dependência real do json.org, mesmo pacote, substitui o
    // stub no classpath de teste — sem precisar de Robolectric só para isso.
    testImplementation(libs.org.json)
    // Robolectric: só para o que precisa de verdade da máquina do Android
    // (SQLite, SharedPreferences) — a fila durável de proof-of-play é o
    // coração do projeto, e testá-la contra um SQLite de mentira não provaria
    // nada. Roda no JVM puro, sem emulador nem dispositivo.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
