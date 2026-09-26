package br.com.mostrai.player.kiosk

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import br.com.mostrai.player.PlayerActivity
import br.com.mostrai.player.config.ConfigAparelho
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Os quatro estados do watchdog, pelo receptor de verdade e pela credencial
 * de verdade: antes da instalação não puxa o Player de volta (o instalador
 * pode estar configurando o Wi-Fi); instalado, puxa; saída por PIN
 * desarma; abrir o Player de novo rearma.
 */
@RunWith(RobolectricTestRunner::class)
class WatchdogInstalacaoTest {

    private val contexto = ApplicationProvider.getApplicationContext<Context>()
    private val app = shadowOf(contexto as Application)
    private val alarmes = shadowOf(contexto.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

    @Before
    fun preparar() {
        // Nenhum sinal de vida: o Player está fora da frente há tempo demais.
        contexto.getSharedPreferences("mostrai_watchdog", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun dispararAlarme() = Watchdog.Receptor().onReceive(contexto, Intent())

    private fun instalar() = check(ConfigAparelho(contexto).gravarCredenciais("M-0235", "k".repeat(43)))

    @Test
    fun `nao provisionado, o watchdog nao traz o Player de volta mas segue agendado`() {
        dispararAlarme()

        assertNull("puxou o Player de volta antes da instalação", app.nextStartedActivity)
        assertNotNull("sem alarme, a TV instalada depois ficaria sem watchdog", alarmes.nextScheduledAlarm)
    }

    @Test
    fun `provisionado, o watchdog traz o Player de volta`() {
        instalar()

        dispararAlarme()

        assertEquals(PlayerActivity::class.java.name, app.nextStartedActivity?.component?.className)
    }

    @Test
    fun `saida autorizada por PIN desarma o watchdog`() {
        instalar()
        Watchdog.autorizarSaida(contexto)

        dispararAlarme()

        assertNull("reabriu depois da saída autorizada", app.nextStartedActivity)
        assertNull("continuou agendado depois da saída autorizada", alarmes.nextScheduledAlarm)
    }

    @Test
    fun `abrir o Player a mao de novo rearma o watchdog`() {
        instalar()
        Watchdog.autorizarSaida(contexto)
        PlayerActivity.introJaTocou = true

        Robolectric.buildActivity(PlayerActivity::class.java).setup().stop()
        // A Activity saiu da frente (HOME) e o sinal de vida envelheceu.
        contexto.getSharedPreferences("mostrai_watchdog", Context.MODE_PRIVATE).edit().clear().commit()
        app.clearNextStartedActivities()

        assertFalse(Watchdog.saidaAutorizada(contexto))
        assertNotNull(alarmes.nextScheduledAlarm)
        dispararAlarme()
        assertEquals(PlayerActivity::class.java.name, app.nextStartedActivity?.component?.className)
    }
}
