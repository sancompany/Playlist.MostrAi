package br.com.mostrai.player.atualizacao

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import br.com.mostrai.player.PlayerActivity
import br.com.mostrai.player.config.ConfigAparelho
import br.com.mostrai.player.estado.DiarioBordo
import br.com.mostrai.player.kiosk.Watchdog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Depois da troca do APK: os dados do app (credencial, fila, diário) ficam
 * — o Android não apaga nada numa atualização com a mesma assinatura —, a
 * cópia do APK some e o Player volta à frente.
 */
@RunWith(RobolectricTestRunner::class)
class AtualizacaoConcluidaReceiverTest {

    private val contexto: Context = ApplicationProvider.getApplicationContext()
    private val app = shadowOf(contexto as Application)
    private val copia get() = File(contexto.filesDir, "atualizacao/Mostrai-Player.apk")

    @Before
    fun preparar() {
        listOf(ConfigAparelho.ARQUIVO, "mostrai_watchdog", "mostrai_atualizacao").forEach {
            contexto.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        contexto.deleteDatabase(DiarioBordo.NOME_ARQUIVO)
        check(ConfigAparelho(contexto).gravarCredenciais("M-0235", "k".repeat(43)))
        copia.parentFile!!.mkdirs()
        copia.writeBytes(ApkFalso.bytes())
        app.clearNextStartedActivities()
    }

    @Test
    fun `versao nova instalada limpa a copia, registra e traz o Player de volta`() {
        // O técnico saiu pelo PIN antes? A atualização é dele: o Player volta.
        Watchdog.autorizarSaida(contexto)
        Watchdog.pausarRetorno(contexto)

        AtualizacaoConcluidaReceiver().onReceive(contexto, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        assertFalse("cópia apagada", copia.exists())
        assertTrue("credencial intacta", ConfigAparelho(contexto).provisionado)
        assertEquals("M-0235", ConfigAparelho(contexto).dispositivoId)
        assertFalse(Watchdog.saidaAutorizada(contexto))
        assertEquals(PlayerActivity::class.java.name, app.nextStartedActivity?.component?.className)
        assertNotNull(EstadoAtualizacao.ultimaAtualizacao(contexto))
        assertTrue(DiarioBordo(contexto).ultimos(10).any { it.codigo == DiarioBordo.Codigo.ATUALIZADO.name })
    }

    @Test
    fun `outro broadcast nao faz nada`() {
        AtualizacaoConcluidaReceiver().onReceive(contexto, Intent(Intent.ACTION_PACKAGE_REPLACED))
        assertTrue(copia.exists())
        assertNull(app.nextStartedActivity)
    }
}
