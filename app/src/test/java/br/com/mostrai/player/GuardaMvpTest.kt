package br.com.mostrai.player

import br.com.mostrai.player.config.ConfigAparelho
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O que o MVP removeu não volta sem alguém perceber (contrato MVP, "o que
 * fica fixo no APK" e "rotas"). Varre o código-fonte e o manifesto — os
 * testes rodam com o diretório do módulo `app` como diretório de trabalho.
 */
class GuardaMvpTest {

    private val raiz = File("src/main")
    private val manifesto by lazy { File(raiz, "AndroidManifest.xml").readText() }
    private val fontes by lazy { raiz.walkTopDown().filter { it.extension == "kt" }.toList() }
    private val codigo by lazy { fontes.associate { it.name to it.readText() } }

    private fun ausente(trecho: String, onde: String = "o código de produção") {
        val achados = codigo.filterValues { it.contains(trecho) }.keys
        assertTrue("'$trecho' voltou a aparecer em $onde: $achados", achados.isEmpty())
    }

    @Test
    fun `manifesto tem so o que o produto usa`() {
        listOf(
            "android.intent.category.HOME",
            "BIND_DEVICE_ADMIN", "PainelActivity", "ReceptorInstalacao", "<service",
            // V1 de produção (05/10/2026): sem câmera/microfone, sem serviço em
            // primeiro plano, sem alarme exato (o retorno usa set() no 12+).
            "CAMERA", "RECORD_AUDIO", "FOREGROUND_SERVICE", "SCHEDULE_EXACT_ALARM", "USE_EXACT_ALARM",
            "usesCleartextTraffic=\"true\"",
            // 3.0.1 (docs/permissoes-especiais.md): o updater por pendrive não
            // pede "todos os arquivos", nem instala sozinho, nem mexe em
            // configuração do sistema; nada de bateria, uso ou escrita.
            "MANAGE_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "android.permission.INSTALL_PACKAGES",
            "REQUEST_DELETE_PACKAGES", "WRITE_SETTINGS", "PACKAGE_USAGE_STATS",
            "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "TURN_SCREEN_ON", "QUERY_ALL_PACKAGES",
        ).forEach { assertFalse("manifesto voltou a declarar $it", manifesto.contains(it)) }
        assertEquals(1, Regex("<activity\\b").findAll(manifesto).count())
        // INTERNET, ACCESS_NETWORK_STATE, RECEIVE_BOOT_COMPLETED,
        // SYSTEM_ALERT_WINDOW (exceção do retorno em segundo plano no 10+,
        // docs/android-modernizacao.md) e, desde a 3.0.1, a atualização por
        // pendrive (REQUEST_INSTALL_PACKAGES, READ_EXTERNAL_STORAGE até o
        // Android 10) e o teste de ligar a tela (WAKE_LOCK). Nenhuma outra
        // sem decisão registrada em docs/permissoes-especiais.md.
        assertEquals(
            setOf(
                "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.RECEIVE_BOOT_COMPLETED", "android.permission.SYSTEM_ALERT_WINDOW",
                "android.permission.REQUEST_INSTALL_PACKAGES", "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WAKE_LOCK",
            ),
            Regex("<uses-permission[^>]*?android:name=\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL)
                .findAll(manifesto).map { it.groupValues[1] }.toSet(),
        )
        assertEquals(7, Regex("<uses-permission\\b").findAll(manifesto).count())
        assertTrue(
            "READ_EXTERNAL_STORAGE só até o Android 10",
            Regex("READ_EXTERNAL_STORAGE\"\\s+android:maxSdkVersion=\"29\"").containsMatchIn(manifesto),
        )
        assertTrue(manifesto.contains("android.intent.category.LEANBACK_LAUNCHER"))
        assertTrue(manifesto.contains("android.intent.action.BOOT_COMPLETED"))
    }

    @Test
    fun `sem OTA - atualizacao so por pendrive, nunca pela rede`() {
        listOf("PackageInstaller", "Atualizador", "UpdateManifesto", "INSTALL_PACKAGES\"", "installPackage(").forEach { ausente(it) }
        assertFalse(File(raiz, "java/br/com/mostrai/player/update").exists())
        // O updater da 3.0.1 lê só pendrive: nada de rede no pacote dele.
        val atualizacao = File(raiz, "java/br/com/mostrai/player/atualizacao").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(atualizacao.isNotEmpty())
        atualizacao.forEach { f ->
            val texto = f.readText()
            listOf("HttpCliente", "MostraiApi", "HttpURLConnection", "URL(", "java.net.").forEach {
                assertFalse("${f.name} fala com a rede ($it): a atualização é só por pendrive", texto.contains(it))
            }
        }
    }

    @Test
    fun `sem Device Owner, kiosk e lock task`() {
        listOf("DevicePolicyManager", "startLockTask", "setLockTaskPackages", "DeviceAdminReceiver").forEach { ausente(it) }
        assertFalse(File(raiz, "java/br/com/mostrai/player/kiosk/Kiosk.kt").exists())
    }

    @Test
    fun `sem painel tecnico nem gesto de 3 toques`() {
        assertFalse(File(raiz, "java/br/com/mostrai/player/ui/PainelActivity.kt").exists())
        assertFalse(File(raiz, "java/br/com/mostrai/player/ui/GestoPainel.kt").exists())
        assertFalse(File(raiz, "res/layout/activity_painel.xml").exists())
    }

    @Test
    fun `sem contrato V1 nem hello`() {
        listOf("/hello", "HelloJson", "enviarLegado", "parseLegado", "modoDegradado", "backendV2", "X-Aparelho-Id", "X-Player-Contract")
            .forEach { ausente(it) }
    }

    @Test
    fun `sem provisionamento por JSON, pendrive ou adb`() {
        listOf("mostrai-config.json", "ConfigExterna", "configDispositivo", "_EMBUTID", "tokenProvisionamento", "getStringExtra", "extras?.getString")
            .forEach { ausente(it) }
        // O único campo de build é a impressão digital pública da chave
        // definitiva (updater por pendrive) — nada por tela, nada secreto.
        val campos = Regex("buildConfigField\\(\"String\", \"(\\w+)\"").findAll(File("build.gradle.kts").readText())
            .map { it.groupValues[1] }.toList()
        assertEquals(listOf("CERTIFICADO_OFICIAL_SHA256"), campos)
        assertEquals(
            File("../scripts/certificado-producao.sha256").readText().trim(),
            BuildConfig.CERTIFICADO_OFICIAL_SHA256,
        )
    }

    @Test
    fun `servidor e rotacao sao constantes, nao estado`() {
        val membros = ConfigAparelho::class.java.declaredMethods.map { it.name.lowercase() }
        listOf("baseurl", "rotacao", "chavecandidata").forEach { nome ->
            assertTrue("ConfigAparelho voltou a guardar $nome", membros.none { it.contains(nome) })
        }
        assertEquals("https://mostrai.sancocore.com.br", Produto.BASE_URL)
        assertEquals(90, Produto.ROTACAO_GRAUS)
        assertEquals(15_000L, Produto.INTERVALO_HEARTBEAT_MS)
    }

    @Test
    fun `na build de release o servidor nao tem ponto de troca`() {
        val release = File("src/release/java/br/com/mostrai/player/HostDaApi.kt").readText()
        assertFalse("release com host mutável", release.contains("var "))
        assertTrue(release.contains("Produto.BASE_URL"))
    }

    @Test
    fun `a API do Player usa exatamente as rotas do contrato`() {
        val api = codigo.getValue("MostraiApi.kt")
        val rotas = Regex("\"\\$\\{base\\(\\)}(/[^\"]*)\"").findAll(api).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "/player/provisionar", "/playlist/\$id", "/player/\$id/played", "/player/\$id/heartbeat",
                "/player/\$id/config",
                // Ponto Móvel (02/10/2026): sessões operacionais registradas offline.
                "/player/\$id/operacao",
            ),
            rotas,
        )
    }
}
