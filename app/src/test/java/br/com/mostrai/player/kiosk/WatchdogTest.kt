package br.com.mostrai.player.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchdogTest {

    @Test
    fun `player dando sinal de vida recente nao e reaberto`() {
        val decisao = Watchdog.decidir(vivoEmMs = 1_000_000, agoraMs = 1_060_000, tentativas = 0)

        assertFalse(decisao.abrirPlayer)
        assertEquals(Watchdog.INTERVALO_BASE_MS, decisao.proximoAtrasoMs)
    }

    @Test
    fun `silencio maior que a tolerancia reabre o player`() {
        val decisao = Watchdog.decidir(
            vivoEmMs = 1_000_000,
            agoraMs = 1_000_000 + Watchdog.TOLERANCIA_MS + 1,
            tentativas = 0,
        )

        assertTrue(decisao.abrirPlayer)
    }

    @Test
    fun `sem nenhum sinal registrado reabre`() {
        assertTrue(Watchdog.decidir(vivoEmMs = 0, agoraMs = 500_000, tentativas = 0).abrirPlayer)
    }

    @Test
    fun `sinal no futuro e resquicio de reboot, nao sinal de vida`() {
        // O relógio monotônico zera no boot: um "vivoEm" maior que "agora"
        // veio do boot anterior, então o player desta sessão nunca apareceu.
        val decisao = Watchdog.decidir(vivoEmMs = 900_000, agoraMs = 10_000, tentativas = 0)

        assertTrue(decisao.abrirPlayer)
    }

    @Test
    fun `tentativas consecutivas afastam as reaberturas`() {
        val agora = 10_000_000L
        val primeira = Watchdog.decidir(vivoEmMs = 0, agoraMs = agora, tentativas = 0)
        val terceira = Watchdog.decidir(vivoEmMs = 0, agoraMs = agora, tentativas = 2)

        assertTrue(terceira.proximoAtrasoMs > primeira.proximoAtrasoMs)
    }

    @Test
    fun `backoff nunca passa do teto`() {
        val decisao = Watchdog.decidir(vivoEmMs = 0, agoraMs = 10_000_000, tentativas = 99)

        assertEquals(Watchdog.INTERVALO_MAXIMO_MS, decisao.proximoAtrasoMs)
    }

    @Test
    fun `player vivo zera o afastamento mesmo depois de tentativas`() {
        val decisao = Watchdog.decidir(vivoEmMs = 1_000_000, agoraMs = 1_010_000, tentativas = 4)

        assertFalse(decisao.abrirPlayer)
        assertEquals(Watchdog.INTERVALO_BASE_MS, decisao.proximoAtrasoMs)
    }
}
