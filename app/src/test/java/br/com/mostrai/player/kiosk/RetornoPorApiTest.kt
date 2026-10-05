package br.com.mostrai.player.kiosk

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import br.com.mostrai.player.BootReceiver
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/**
 * V1 de produção (05/10/2026): o retorno do Player à frente em cada faixa
 * de Android — 8 (a TCL), 10 (bloqueio de abertura do segundo plano), 12
 * (alarme exato pede permissão), 14 e 16. Roda a classe inteira em cada
 * nível; o que muda por nível está nas asserções.
 *
 * Isto prova a decisão do Player em cada nível, no Robolectric. Não prova
 * o firmware de nenhum fabricante — isso é o checklist físico.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 29, 31, 34, 36])
class RetornoPorApiTest {

    private val contexto: Context = ApplicationProvider.getApplicationContext()
    private val alarmes = shadowOf(contexto.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val sdk = Build.VERSION.SDK_INT

    @Before
    fun preparar() {
        contexto.getSharedPreferences("mostrai_watchdog", Context.MODE_PRIVATE).edit().clear().commit()
        contexto.getSharedPreferences(ConfigAparelho.ARQUIVO, Context.MODE_PRIVATE).edit().clear().commit()
        ShadowSettings.setCanDrawOverlays(false)
        check(ConfigAparelho(contexto).gravarCredenciais("M-0235", "k".repeat(43)))
    }

    @Test
    fun `politica pura por nivel`() {
        assertTrue(PoliticaDeRetorno.podeAbrirDoFundo(26, sobreposicaoPermitida = false))
        assertTrue(PoliticaDeRetorno.podeAbrirDoFundo(28, sobreposicaoPermitida = false))
        assertFalse(PoliticaDeRetorno.podeAbrirDoFundo(29, sobreposicaoPermitida = false))
        assertTrue(PoliticaDeRetorno.podeAbrirDoFundo(29, sobreposicaoPermitida = true))
        assertFalse(PoliticaDeRetorno.podeAbrirDoFundo(36, sobreposicaoPermitida = false))
        assertTrue(PoliticaDeRetorno.podeAbrirDoFundo(36, sobreposicaoPermitida = true))
        assertTrue(PoliticaDeRetorno.usaAlarmeExato(30))
        assertFalse(PoliticaDeRetorno.usaAlarmeExato(31))
    }

    @Test
    fun `HOME agenda o retorno em 5 s, exato so onde nao pede permissao`() {
        Watchdog.saiuDaFrente(contexto)

        val retorno = alarmes.scheduledAlarms.single { shadowOf(it.operation).savedIntent.action == Watchdog.ACAO_RETORNO }
        if (sdk < 31) {
            assertEquals("Android $sdk: setExact (janela 0)", 0L, retorno.windowLengthMs)
        } else {
            assertTrue("Android $sdk: setExact sem SCHEDULE_EXACT_ALARM", retorno.windowLengthMs != 0L)
        }
        assertEquals(AlarmManager.ELAPSED_REALTIME, retorno.type)
    }

    @Test
    fun `boot rearma o watchdog e tenta abrir o Player em todo nivel`() {
        BootReceiver().onReceive(contexto, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertNotNull(alarmes.nextScheduledAlarm)
        val aberta = shadowOf(contexto as Application).nextStartedActivity
        assertEquals(PlayerActivity::class.java.name, aberta.component?.className)
    }

    @Test
    fun `sem Exibir sobre outros apps, o bloqueio do 10+ fica registrado para o suporte`() {
        Watchdog.abrirPlayer(contexto)

        if (sdk >= 29) {
            assertNotNull("Android $sdk bloqueia em silêncio: o suporte precisa saber", Watchdog.retornoBloqueadoEm(contexto))
        } else {
            assertNull("Android $sdk abre do segundo plano sem permissão especial", Watchdog.retornoBloqueadoEm(contexto))
        }
    }

    @Test
    fun `com Exibir sobre outros apps concedida, nada fica registrado como bloqueado`() {
        ShadowSettings.setCanDrawOverlays(true)

        Watchdog.abrirPlayer(contexto)

        assertNull(Watchdog.retornoBloqueadoEm(contexto))
    }
}
