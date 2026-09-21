import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/**
 * Configuração de um aparelho específico, embutida no build via
 * `-PconfigDispositivo=<arquivo>.json` (ver README, "Gerar um APK já
 * configurado por tela"). Sem essa propriedade, os cinco campos ficam
 * vazios e o app se comporta exatamente como antes: precisa de
 * provisionamento por adb ou tela.
 *
 * O arquivo em si NUNCA é commitado (só `dispositivos/exemplo.json.example`
 * é) — a chave revogável de um aparelho real não é segredo versionado
 * (CONSTRAINTS.md), fica só no APK que você mesmo gera localmente.
 */
data class ConfigEmbutidaDoDispositivo(
    val dispositivoId: String = "",
    val chaveAparelho: String = "",
    val baseUrl: String = "",
    val pin: String = "",
    val margemVmin: String = "",
    val rotacaoTela: String = "",
)

fun lerConfigDispositivo(): ConfigEmbutidaDoDispositivo {
    val caminho = project.findProperty("configDispositivo") as String? ?: return ConfigEmbutidaDoDispositivo()
    val arquivo = rootProject.file(caminho)
    check(arquivo.exists()) { "configDispositivo apontou para um arquivo que não existe: ${arquivo.absolutePath}" }

    @Suppress("UNCHECKED_CAST")
    val json = JsonSlurper().parse(arquivo) as Map<String, Any?>
    return ConfigEmbutidaDoDispositivo(
        dispositivoId = json["dispositivoId"] as? String ?: "",
        chaveAparelho = json["chaveAparelho"] as? String ?: "",
        baseUrl = json["baseUrl"] as? String ?: "",
        pin = json["pin"] as? String ?: "",
        margemVmin = json["margemVmin"]?.toString() ?: "",
        rotacaoTela = json["rotacaoTela"]?.toString() ?: "",
    )
}

/** Escapa para virar literal de String em Kotlin/Java gerado no BuildConfig. */
fun paraLiteralJava(valor: String): String =
    "\"" + valor.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val configDispositivo = lerConfigDispositivo()

android {
    namespace = "br.com.mostrai.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.mostrai.player"
        minSdk = 26
        targetSdk = 26
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "DISPOSITIVO_ID_EMBUTIDO", paraLiteralJava(configDispositivo.dispositivoId))
        buildConfigField("String", "CHAVE_APARELHO_EMBUTIDA", paraLiteralJava(configDispositivo.chaveAparelho))
        buildConfigField("String", "BASE_URL_EMBUTIDA", paraLiteralJava(configDispositivo.baseUrl))
        buildConfigField("String", "PIN_EMBUTIDO", paraLiteralJava(configDispositivo.pin))
        buildConfigField("String", "MARGEM_VMIN_EMBUTIDA", paraLiteralJava(configDispositivo.margemVmin))
        buildConfigField("String", "ROTACAO_TELA_EMBUTIDA", paraLiteralJava(configDispositivo.rotacaoTela))
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
