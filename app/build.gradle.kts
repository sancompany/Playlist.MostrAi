import groovy.json.JsonSlurper
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/**
 * Configuração de um aparelho específico, embutida no build via
 * `-PconfigDispositivo=<arquivo>.json` (ver README, "Gerar um APK já
 * configurado por tela"). Sem essa propriedade, os oito campos ficam
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
    val margemVminTopo: String = "",
    val margemVminBase: String = "",
    val margemVminEsquerda: String = "",
    val margemVminDireita: String = "",
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
        margemVminTopo = json["margemVminTopo"]?.toString() ?: "",
        margemVminBase = json["margemVminBase"]?.toString() ?: "",
        margemVminEsquerda = json["margemVminEsquerda"]?.toString() ?: "",
        margemVminDireita = json["margemVminDireita"]?.toString() ?: "",
        rotacaoTela = json["rotacaoTela"]?.toString() ?: "",
    )
}

/** Escapa para virar literal de String em Kotlin/Java gerado no BuildConfig. */
fun paraLiteralJava(valor: String): String =
    "\"" + valor.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val configDispositivo = lerConfigDispositivo()

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
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.mostrai.player"
        minSdk = 26
        targetSdk = 26
        // Toda atualização OTA compara `versionCode`. Subir aqui é o que faz
        // um player em campo reconhecer que existe versão nova.
        versionCode = 2
        versionName = "1.0.0"

        buildConfigField("String", "DISPOSITIVO_ID_EMBUTIDO", paraLiteralJava(configDispositivo.dispositivoId))
        buildConfigField("String", "CHAVE_APARELHO_EMBUTIDA", paraLiteralJava(configDispositivo.chaveAparelho))
        buildConfigField("String", "BASE_URL_EMBUTIDA", paraLiteralJava(configDispositivo.baseUrl))
        buildConfigField("String", "PIN_EMBUTIDO", paraLiteralJava(configDispositivo.pin))
        buildConfigField("String", "MARGEM_VMIN_TOPO_EMBUTIDA", paraLiteralJava(configDispositivo.margemVminTopo))
        buildConfigField("String", "MARGEM_VMIN_BASE_EMBUTIDA", paraLiteralJava(configDispositivo.margemVminBase))
        buildConfigField(
            "String", "MARGEM_VMIN_ESQUERDA_EMBUTIDA", paraLiteralJava(configDispositivo.margemVminEsquerda),
        )
        buildConfigField(
            "String", "MARGEM_VMIN_DIREITA_EMBUTIDA", paraLiteralJava(configDispositivo.margemVminDireita),
        )
        buildConfigField("String", "ROTACAO_TELA_EMBUTIDA", paraLiteralJava(configDispositivo.rotacaoTela))
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
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // Regra de política da Play Store. Este app é sideload e nunca vai à
        // loja; targetSdk 26 é decisão deliberada (README, "Alvo"): subir
        // traria restrições de background e foreground service que só
        // atrapalham um player de quiosque. Desligar só esta regra mantém o
        // lint útil como sinal para todo o resto.
        disable += "ExpiredTargetSdkVersion"
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
