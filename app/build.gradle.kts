import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/**
 * Credenciais de assinatura, lidas de `keystore.properties` na raiz do
 * projeto — arquivo que **nunca** entra no Git (ver `.gitignore`).
 *
 * Ausente, o build de release sai sem `signingConfig` e o Gradle recusa
 * gerar o APK assinado. Isso é intencional: um release assinado com a chave
 * de depuração instalado numa TV não poderia mais ser atualizado pela chave
 * de verdade depois — o Android recusa a troca de assinatura, e a única
 * saída seria desinstalar, perdendo identidade e fila de proof-of-play.
 * Falhar aqui custa um minuto; descobrir em campo custa uma visita por tela.
 *
 * Ver `RUNBOOK.md`, "Chave de assinatura".
 */
fun lerPropriedadesDeAssinatura(): Properties? {
    val arquivo = rootProject.file("keystore.properties")
    if (!arquivo.exists()) return null
    val propriedades = Properties()
    arquivo.inputStream().use { propriedades.load(it) }
    return propriedades
}

val propriedadesAssinatura = lerPropriedadesDeAssinatura()

android {
    namespace = "br.com.mostrai.player"
    // API 36 (Android 16): o Android TV mais novo que existe (Android 16 for
    // TV). O 37 é estável desde 06/2026, mas nenhuma TV roda 37 e ele exige
    // AGP 9 — docs/adr/0001-target-sdk-moderno.md.
    compileSdk = 36

    defaultConfig {
        applicationId = "br.com.mostrai.player"
        // Piso: SEMP TCL 32S6500S (Android TV 8.0). O alvo é o Android atual —
        // o app suporta a TCL sem se declarar feito para 2017.
        minSdk = 26
        targetSdk = 36
        // Atualização é manual (sideload). O Android só instala por cima de
        // uma versão com `versionCode` menor — subir a cada build de campo.
        // 3 / 2.0.0: Player MVP (contrato docs/player-mvp-contract.md).
        // 4 / 3.0.0: V1 de produção — target 36, segmentos operacionais,
        // offline endurecido, release assinado (docs/release-producao.md).
        // Teste N → N+1 com a mesma chave (docs/release-producao.md): só pela
        // linha de comando (`scripts/release-teste-n-mais-1.sh`), nunca
        // commitado. O versionName marcado faz o candidato oficial recusar
        // esse APK.
        val versionCodeTeste = (findProperty("mostrai.versionCodeTeste") as String?)?.toInt()
        versionCode = versionCodeTeste ?: 4
        versionName = if (versionCodeTeste != null) "3.0.0-teste-n$versionCodeTeste" else "3.0.0"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (propriedadesAssinatura != null) {
            create("release") {
                storeFile = rootProject.file(propriedadesAssinatura.getProperty("storeFile"))
                storePassword = propriedadesAssinatura.getProperty("storePassword")
                keyAlias = propriedadesAssinatura.getProperty("keyAlias")
                keyPassword = propriedadesAssinatura.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sem keystore.properties o release fica sem assinatura de
            // produção de propósito — ver lerPropriedadesDeAssinatura().
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        // Lint vale como portão (CI roda debug e release): erro quebra o build.
        abortOnError = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Robolectric guarda um sandbox por nível de SDK (RetornoPorApiTest
                // roda em 26, 29, 31, 34 e 36): os 512 MB padrão estouram.
                it.maxHeapSize = "2g"
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
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
