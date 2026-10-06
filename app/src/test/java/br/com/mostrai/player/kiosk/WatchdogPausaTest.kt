package br.com.mostrai.player.kiosk

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import br.com.mostrai.player.PlayerActivity
import br.com.mostrai.player.config.ConfigAparelho
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

/**
 * Atualização por pendrive: o técnico precisa usar o instalador e as
 * Configurações do Android sem o retorno rápido puxar o Player por cima — e
 * sem o Player ficar fora da frente para sempre se ninguém voltar.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class WatchdogPausaTest {

    private val contexto: Context = ApplicationProvider.getApplicationContext()
    private val app = shadowOf(contexto as Application)
    private val alarmes = shadowOf(contexto.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

    @Before
    fun preparar() {
        contexto.getSharedPreferences("mostrai_watchdog", Context.MODE_PRIVATE).edit().clear().commit()
        contexto.getSharedPreferences(ConfigAparelho.ARQUIVO, Context.MODE_PRIVATE).edit().clear().commit()
        PlayerActivity.introJaTocou = true
        br.com.mostrai.player.HostDaApi.base = "http://127.0.0.1:1"
        check(ConfigAparelho(contexto).gravarCredenciais("M-0235", "k".repeat(43)))
    }

    @After
    fun restaurar() {
        br.com.mostrai.player.HostDaApi.base = br.com.mostrai.player.Produto.BASE_URL
    }

    private fun alarmesDeRetorno() =
        alarmes.scheduledAlarms.filter { shadowOf(it.operation).savedIntent.action == Watchdog.ACAO_RETORNO }

    private fun avancar(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun `pausa vale ate o teto e nunca alem dele`() {
        assertTrue(Watchdog.pausado(pausaAteMs = 1_000_000, agoraMs = 999_000))
        assertFalse("passou", Watchdog.pausado(pausaAteMs = 1_000_000, agoraMs = 1_000_000))
        assertFalse(
            "pausa maior que o teto é de outro boot",
            Watchdog.pausado(pausaAteMs = 1_000_000 + Watchdog.PAUSA_MANUTENCAO_MS + 1, agoraMs = 1_000_000),
        )
    }

    @Test
    fun `sair para o instalador com pausa nao agenda o retorno rapido`() {
        val controle = Robolectric.buildActivity(PlayerActivity::class.java).setup()
        Watchdog.pausarRetorno(contexto)
        controle.pause().stop()
        assertTrue(alarmesDeRetorno().isEmpty())
    }

    @Test
    fun `retorno rapido ja agendado nao abre o Player durante a pausa`() {
        Robolectric.buildActivity(PlayerActivity::class.java).setup().pause().stop()
        val retorno = alarmesDeRetorno().single()
        Watchdog.pausarRetorno(contexto)
        app.clearNextStartedActivities()

        Watchdog.Receptor().onReceive(contexto, shadowOf(retorno.operation).savedIntent)

        assertNull(app.nextStartedActivity)
    }

    @Test
    fun `backstop espera a pausa acabar e entao traz o Player`() {
        Robolectric.buildActivity(PlayerActivity::class.java).setup().pause().stop()
        Watchdog.pausarRetorno(contexto, 5 * 60_000L)
        avancar(Watchdog.TOLERANCIA_MS + 1_000)
        app.clearNextStartedActivities()

        Watchdog.Receptor().onReceive(contexto, Intent())
        assertNull("ainda em pausa", app.nextStartedActivity)

        avancar(5 * 60_000L)
        Watchdog.Receptor().onReceive(contexto, Intent())
        assertEquals(PlayerActivity::class.java.name, app.nextStartedActivity?.component?.className)
    }

    @Test
    fun `voltar a frente desfaz a pausa`() {
        val controle = Robolectric.buildActivity(PlayerActivity::class.java).setup()
        Watchdog.pausarRetorno(contexto)
        controle.pause().stop()
        controle.restart().start().resume()

        controle.pause().stop()

        assertEquals("HOME depois disso volta a ter retorno de 5 s", 1, alarmesDeRetorno().size)
    }

    @Test
    fun `instalador em dialogo por cima do Player - so o onResume desfaz a pausa`() {
        val controle = Robolectric.buildActivity(PlayerActivity::class.java).setup()
        Watchdog.pausarRetorno(contexto)
        controle.pause()
        controle.resume()

        controle.pause().stop()

        assertEquals(1, alarmesDeRetorno().size)
    }
}
