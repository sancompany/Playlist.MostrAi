package br.com.mostrai.player.ciclo

import br.com.mostrai.player.PlayerActivity
import br.com.mostrai.player.cache.ServidorDeTeste
import br.com.mostrai.player.config.ConfigAparelho
import br.com.mostrai.player.config.ConfigRemotaJson
import br.com.mostrai.player.estado.EstadoPlayer
import java.util.concurrent.CountDownLatch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.LooperMode

/** Ciclo 9 — fechar no horário com uma exibição ainda resolvendo. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class HorarioCicloTest {

    private lateinit var h: Harness

    @Before
    fun preparar() {
        h = Harness()
    }

    @After
    fun encerrar() = h.encerrar()

    private fun estado(atividade: PlayerActivity): EstadoPlayer {
        val campo = PlayerActivity::class.java.getDeclaredField("estadoAtual")
        campo.isAccessible = true
        return campo.get(atividade) as EstadoPlayer
    }

    @Test
    fun `exibicao que termina de resolver depois do fechamento nao toca nem reabre a tela`() {
        h.provisionar()
        h.servidor.rotas["/playlist"] = ServidorDeTeste.Resposta(corpo = h.playlistComUmVideo().toByteArray())
        h.servidor.rotas["/player"] = ServidorDeTeste.Resposta(codigo = 404)
        val midia = CountDownLatch(1)
        h.servidor.travas["/midia"] = midia
        h.servidor.rotas["/midia"] = ServidorDeTeste.Resposta(corpo = "bytes".toByteArray())

        val atividade = h.subir().get()
        h.esperar { h.servidor.contar("/midia") > 0 } // resolvendo o cache

        // Loja fecha: CUSTOM sem faixa em dia nenhum.
        val fechado = """{"configVersion": 1, "operacao": {"regime": "CUSTOM",
            "porDiaDaSemana": {"seg": [], "ter": [], "qua": [], "qui": [], "sex": [], "sab": [], "dom": []}}}"""
        ConfigAparelho(h.contexto).aplicarConfigRemota(ConfigRemotaJson.parse(fechado)!!, fechado)
        h.avancar(61_000L) // checarHorario
        assertEquals(EstadoPlayer.OUT_OF_SCHEDULE, estado(atividade))

        midia.countDown()
        repeat(25) {
            Thread.sleep(20)
            h.idle()
        }

        assertEquals("anúncio voltou a tocar depois do fechamento", EstadoPlayer.OUT_OF_SCHEDULE, estado(atividade))
        assertEquals("linha órfã na fila", 0, h.orfaos())
    }
}
