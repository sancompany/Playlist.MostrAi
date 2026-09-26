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
            "REQUEST_INSTALL_PACKAGES", "READ_EXTERNAL_STORAGE", "android.intent.category.HOME",
            "BIND_DEVICE_ADMIN", "PainelActivity", "ReceptorInstalacao", "<service",
        ).forEach { assertFalse("manifesto voltou a declarar $it", manifesto.contains(it)) }
        assertEquals(1, Regex("<activity\\b").findAll(manifesto).count())
        assertEquals(3, Regex("<uses-permission\\b").findAll(manifesto).count())
        assertTrue(manifesto.contains("android.intent.category.LEANBACK_LAUNCHER"))
        assertTrue(manifesto.contains("android.intent.action.BOOT_COMPLETED"))
    }

    @Test
    fun `sem OTA`() {
        listOf("PackageInstaller", "Atualizador", "UpdateManifesto", "canRequestPackageInstalls").forEach { ausente(it) }
        assertFalse(File(raiz, "java/br/com/mostrai/player/update").exists())
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
        assertFalse(File("build.gradle.kts").readText().contains("buildConfigField"))
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
    fun `a API do Player usa exatamente as 5 rotas do contrato`() {
        val api = codigo.getValue("MostraiApi.kt")
        val rotas = Regex("\"\\$\\{base\\(\\)}(/[^\"]*)\"").findAll(api).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf("/player/provisionar", "/playlist/\$id", "/player/\$id/played", "/player/\$id/heartbeat", "/player/\$id/config"),
            rotas,
        )
    }
}
