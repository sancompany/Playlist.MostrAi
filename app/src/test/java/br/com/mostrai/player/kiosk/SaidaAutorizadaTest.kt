package br.com.mostrai.player.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Saída autorizada por PIN: o watchdog respeita, e reabrir o app rearma (contrato §6). */
@RunWith(RobolectricTestRunner::class)
class SaidaAutorizadaTest {

    @Test
    fun `alarme depois da saida autorizada nao abre o player`() {
        val contexto = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        Watchdog.autorizarSaida(contexto)

        Watchdog.Receptor().onReceive(contexto, android.content.Intent())

        val app = org.robolectric.Shadows.shadowOf(contexto as android.app.Application)
        assertEquals(null, app.nextStartedActivity)
        assertTrue(Watchdog.saidaAutorizada(contexto))
    }

    @Test
    fun `rearmar desfaz a saida autorizada e agenda o alarme`() {
        val contexto = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        Watchdog.autorizarSaida(contexto)

        Watchdog.rearmar(contexto)

        assertFalse(Watchdog.saidaAutorizada(contexto))
        val alarmes = contexto.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
        org.junit.Assert.assertNotNull(org.robolectric.Shadows.shadowOf(alarmes).nextScheduledAlarm)
    }
}
