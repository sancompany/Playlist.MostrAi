package br.com.mostrai.player.kiosk

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import br.com.mostrai.player.BootReceiver
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Ciclo 15 — o boot arma o watchdog por conta própria. */
@RunWith(RobolectricTestRunner::class)
class BootReceiverTest {

    @Test
    fun `boot agenda o watchdog mesmo antes do player abrir`() {
        // Alarme não sobrevive a reboot. Se o startActivity do boot não
        // pegar (firmware atrasando ou recusando), só o PlayerActivity
        // reagendaria o watchdog — e ele não abriu.
        val contexto = ApplicationProvider.getApplicationContext<Context>()
        val alarmes = contexto.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        BootReceiver().onReceive(contexto, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertNotNull("nenhum alarme de watchdog agendado no boot", shadowOf(alarmes).nextScheduledAlarm)
    }

    @Test
    fun `reboot depois de uma saida autorizada volta a operar`() {
        // A saída por PIN não pode virar uma TV apagada para sempre.
        val contexto = ApplicationProvider.getApplicationContext<Context>()
        Watchdog.autorizarSaida(contexto)

        BootReceiver().onReceive(contexto, Intent(Intent.ACTION_BOOT_COMPLETED))

        org.junit.Assert.assertFalse(Watchdog.saidaAutorizada(contexto))
        val aberta = shadowOf(contexto as android.app.Application).nextStartedActivity
        org.junit.Assert.assertEquals(br.com.mostrai.player.PlayerActivity::class.java.name, aberta.component?.className)
    }
}
