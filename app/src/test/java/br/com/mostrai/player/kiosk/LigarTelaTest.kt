package br.com.mostrai.player.kiosk

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import br.com.mostrai.player.PlayerActivity
import br.com.mostrai.player.config.ConfigAparelho
import br.com.mostrai.player.estado.DiarioBordo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowPowerManager

/**
 * "Ligar tela" é teste disparado pelo técnico, não recurso automático: um
 * alarme que acorda o aparelho, uma wake lock que pede para acender o painel
 * e o registro do que o Android disse. Se o painel acendeu de verdade, só a
 * TV mostra.
 */
@RunWith(RobolectricTestRunner::class)
class LigarTelaTest {

    private val contexto: Context = ApplicationProvider.getApplicationContext()
    private val app = shadowOf(contexto as Application)
    private val alarmes = shadowOf(contexto.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

    @Before
    fun preparar() {
        listOf("mostrai_ligar_tela", "mostrai_watchdog", ConfigAparelho.ARQUIVO).forEach {
            contexto.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        contexto.deleteDatabase(DiarioBordo.NOME_ARQUIVO)
        PlayerActivity.introJaTocou = true
        br.com.mostrai.player.HostDaApi.base = "http://127.0.0.1:1"
    }

    @After
    fun restaurar() {
        br.com.mostrai.player.HostDaApi.base = br.com.mostrai.player.Produto.BASE_URL
    }

    private fun alarmesDoTeste() =
        alarmes.scheduledAlarms.filter { shadowOf(it.operation).savedIntent.action == LigarTela.ACAO }

    @Test
    fun `o tecnico agenda o teste para daqui a 2 minutos, com alarme que acorda o aparelho`() {
        val antes = SystemClock.elapsedRealtime()
        LigarTela.agendarTeste(contexto)

        val alarme = alarmesDoTeste().single()
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarme.type)
        assertEquals(LigarTela.ESPERA_TESTE_MS, alarme.triggerAtTime - antes)
        assertEquals("teste agendado", LigarTela.situacao(contexto))
    }

    @Test
    fun `nada agenda o teste sozinho`() {
        Robolectric.buildActivity(PlayerActivity::class.java).setup().pause().stop()
        assertTrue(alarmesDoTeste().isEmpty())
        assertNull(LigarTela.situacao(contexto))
    }

    @Test
    fun `o disparo pede a tela acesa e abre o Player marcado como teste`() {
        LigarTela.agendarTeste(contexto)
        app.clearNextStartedActivities()

        LigarTela.Receptor().onReceive(contexto, shadowOf(alarmesDoTeste().single().operation).savedIntent)

        val trava = ShadowPowerManager.getLatestWakeLock()
        assertNotNull(trava)
        assertEquals("mostrai:ligar-tela-teste", shadowOf(trava).tag)
        assertTrue(trava!!.isHeld)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(16))
        assertFalse("a trava é por tempo, não fica presa", trava.isHeld)
        val aberto = app.nextStartedActivity
        assertEquals(PlayerActivity::class.java.name, aberto?.component?.className)
        assertTrue(aberto!!.getBooleanExtra(LigarTela.EXTRA_ACORDAR, false))
    }

    @Test
    fun `o Player aberto pelo teste registra o que o Android disse da tela`() {
        shadowOf(contexto.getSystemService(Context.POWER_SERVICE) as PowerManager).setIsInteractive(true)
        val abrir = android.content.Intent(contexto, PlayerActivity::class.java).putExtra(LigarTela.EXTRA_ACORDAR, true)

        Robolectric.buildActivity(PlayerActivity::class.java, abrir).setup()

        assertEquals("Android informou tela ligada após o teste", LigarTela.situacao(contexto))
        assertTrue(DiarioBordo(contexto).ultimos(20).any { it.codigo == DiarioBordo.Codigo.LIGAR_TELA_TESTE.name })
    }

    @Test
    fun `abrir o Player normalmente nao mexe na tela nem registra teste`() {
        Robolectric.buildActivity(PlayerActivity::class.java).setup()
        assertNull(LigarTela.situacao(contexto))
    }
}
