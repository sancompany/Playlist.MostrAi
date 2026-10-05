package br.com.mostrai.player.kiosk

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import br.com.mostrai.player.PlayerActivity
import br.com.mostrai.player.config.ConfigAparelho
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
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Sair do Player só pelo PIN; HOME (que nenhum app intercepta, e se
 * declarar launcher o instalador da TCL recusa) é respondido com o retorno
 * mais rápido que o Android permite.
 */
@RunWith(RobolectricTestRunner::class)
class WatchdogRetornoTest {

    private val contexto: Context = ApplicationProvider.getApplicationContext()
    private val app = shadowOf(contexto as Application)
    private val alarmes = shadowOf(contexto.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

    @Before
    fun preparar() {
        contexto.getSharedPreferences("mostrai_watchdog", Context.MODE_PRIVATE).edit().clear().commit()
        contexto.getSharedPreferences(ConfigAparelho.ARQUIVO, Context.MODE_PRIVATE).edit().clear().commit()
        PlayerActivity.introJaTocou = true
        // Nunca a produção: uma chave falsa tomaria 401 e apagaria a credencial no meio do teste.
        br.com.mostrai.player.HostDaApi.base = "http://127.0.0.1:1"
    }

    @org.junit.After
    fun restaurar() {
        br.com.mostrai.player.HostDaApi.base = br.com.mostrai.player.Produto.BASE_URL
    }

    private fun instalar() = check(ConfigAparelho(contexto).gravarCredenciais("M-0235", "k".repeat(43)))

    private fun alarmesDeRetorno(): List<ShadowAlarmManager.ScheduledAlarm> =
        alarmes.scheduledAlarms.filter { shadowOf(it.operation).savedIntent.action == Watchdog.ACAO_RETORNO }

    private fun disparar(alarme: ShadowAlarmManager.ScheduledAlarm) =
        Watchdog.Receptor().onReceive(contexto, shadowOf(alarme.operation).savedIntent)

    @Test
    fun `decisao do retorno respeita PIN, instalacao, frente e tela desligada`() {
        assertTrue(Watchdog.decidirRetorno(false, true, false, true, 0).abrirPlayer)
        assertEquals(10_000L, Watchdog.decidirRetorno(false, true, false, true, 0).proximoAtrasoMs)
        assertNull("depois da última tentativa fica com o backstop", Watchdog.decidirRetorno(false, true, false, true, 4).proximoAtrasoMs)
        assertFalse("saída por PIN", Watchdog.decidirRetorno(true, true, false, true, 0).abrirPlayer)
        assertFalse("sem instalação", Watchdog.decidirRetorno(false, false, false, true, 0).abrirPlayer)
        assertFalse("já na frente", Watchdog.decidirRetorno(false, true, true, true, 0).abrirPlayer)
        assertFalse("TV desligada (standby)", Watchdog.decidirRetorno(false, true, false, false, 0).abrirPlayer)
    }

    @Test
    fun `HOME com a tela instalada agenda o retorno em 5 segundos`() {
        instalar()
        val controle = Robolectric.buildActivity(PlayerActivity::class.java).setup()
        assertTrue("já agendado com o Player na frente", alarmesDeRetorno().isEmpty())

        val antes = SystemClock.elapsedRealtime()
        controle.pause().stop()

        val retorno = alarmesDeRetorno().single()
        assertEquals(5_000L, retorno.triggerAtTime - antes)
        // Exato até o Android 11; do 12 em diante `set` (sem permissão de
        // alarme exato), que o AlarmManager não adia abaixo de 10 s.
        // Os cinco níveis: RetornoPorApiTest.
        if (android.os.Build.VERSION.SDK_INT < 31) {
            assertEquals("inexato atrasaria até 75%", ShadowAlarmManager.WINDOW_EXACT, retorno.windowLengthMs)
        } else {
            assertTrue(retorno.windowLengthMs != ShadowAlarmManager.WINDOW_EXACT)
        }
    }

    @Test
    fun `o retorno traz o Player e tenta de novo se o launcher nao deixar`() {
        instalar()
        Robolectric.buildActivity(PlayerActivity::class.java).setup().pause().stop()
        app.clearNextStartedActivities()

        disparar(alarmesDeRetorno().single())

        assertEquals(PlayerActivity::class.java.name, app.nextStartedActivity?.component?.className)
        val proxima = alarmesDeRetorno().single()
        assertEquals(10_000L, proxima.triggerAtTime - SystemClock.elapsedRealtime())
    }

    @Test
    fun `voltar para a frente cancela o retorno pendente`() {
        instalar()
        val controle = Robolectric.buildActivity(PlayerActivity::class.java).setup().pause().stop()
        assertNotNull(alarmesDeRetorno().singleOrNull())

        controle.restart().start().resume()

        assertTrue(alarmesDeRetorno().isEmpty())
    }

    @Test
    fun `TV em standby nao e acordada pelo retorno`() {
        instalar()
        Robolectric.buildActivity(PlayerActivity::class.java).setup().pause().stop()
        shadowOf(contexto.getSystemService(Context.POWER_SERVICE) as PowerManager).setIsInteractive(false)
        app.clearNextStartedActivities()

        disparar(alarmesDeRetorno().single())

        assertNull(app.nextStartedActivity)
    }

    @Test
    fun `backstop nao reabre o Player com a TV em standby, mas segue vigiando`() {
        instalar()
        shadowOf(contexto.getSystemService(Context.POWER_SERVICE) as PowerManager).setIsInteractive(false)
        app.clearNextStartedActivities()

        Watchdog.Receptor().onReceive(contexto, Intent())

        assertNull(app.nextStartedActivity)
        assertNotNull(alarmes.nextScheduledAlarm)
    }

    @Test
    fun `saida pelo PIN nao agenda retorno nenhum`() {
        instalar()
        val controle = Robolectric.buildActivity(PlayerActivity::class.java).setup()
        Watchdog.autorizarSaida(contexto)
        controle.pause().stop()
        app.clearNextStartedActivities()

        assertTrue(alarmesDeRetorno().isEmpty())
        Watchdog.Receptor().onReceive(contexto, Intent(Watchdog.ACAO_RETORNO))
        assertNull(app.nextStartedActivity)
    }

    @Test
    fun `antes da instalacao o HOME nao puxa o Player de volta`() {
        val controle = Robolectric.buildActivity(PlayerActivity::class.java).setup()
        controle.pause().stop()

        assertTrue(alarmesDeRetorno().isEmpty())
    }

    @Test
    fun `backstop de crash reabre em ate um minuto e meio sem sinal`() {
        val silencio = Watchdog.decidir(vivoEmMs = 1_000, agoraMs = 1_000 + Watchdog.TOLERANCIA_MS + 1, tentativas = 0)
        assertTrue(silencio.abrirPlayer)
        assertTrue(Watchdog.TOLERANCIA_MS <= 90_000L)
        assertTrue(PlayerActivity.INTERVALO_SINAL_DE_VIDA_MS * 2 < Watchdog.TOLERANCIA_MS)
    }
}
