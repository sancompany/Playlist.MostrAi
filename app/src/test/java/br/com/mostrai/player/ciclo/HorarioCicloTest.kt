package br.com.mostrai.player.ciclo

import br.com.mostrai.player.PlayerActivity
import br.com.mostrai.player.cache.ServidorDeTeste
import br.com.mostrai.player.config.ConfigAparelho
import br.com.mostrai.player.estado.EstadoPlayer
import java.util.concurrent.CountDownLatch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.LooperMode

/** Horário do ponto chegando pela config com uma exibição ainda resolvendo (BUG-025). */
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

    private fun estado(atividade: PlayerActivity) = h.campo<EstadoPlayer>(atividade, "estadoAtual")

    @Test
    fun `exibicao que termina de resolver depois do fechamento nao toca nem reabre a tela`() {
        h.provisionar()
        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(200, h.playlistComUmVideo())
        val midia = CountDownLatch(1)
        h.servidor.travas["/midia"] = midia
        h.servidor.rotas["/midia"] = ServidorDeTeste.Resposta(200, "bytes")
        // O admin fecha o ponto (todos os dias sem faixa): chega pela config,
        // mas só depois que a exibição já está resolvendo o cache.
        val heartbeat = CountDownLatch(1)
        h.servidor.travas["/player/M-0001/heartbeat"] = heartbeat
        h.servidor.rotas["/player/M-0001/heartbeat"] = ServidorDeTeste.Resposta(200, """{"configVersion":1}""")
        h.servidor.rotas["/player/M-0001/config"] = ServidorDeTeste.Resposta(
            200,
            """{"configVersion":1,"operacao":{"timezone":"America/Sao_Paulo","porDiaDaSemana":
               {"seg":[],"ter":[],"qua":[],"qui":[],"sex":[],"sab":[],"dom":[]}}}""",
        )

        val atividade = h.subir().get()
        h.esperar { h.orfaos() == 1 } // exibição registrada, cache preso
        heartbeat.countDown()
        h.esperar { estado(atividade) == EstadoPlayer.OUT_OF_SCHEDULE }
        assertEquals(1, ConfigAparelho(h.contexto).configVersionAplicada)

        midia.countDown()
        repeat(25) {
            Thread.sleep(20)
            h.idle()
        }

        assertEquals("anúncio voltou a tocar depois do fechamento", EstadoPlayer.OUT_OF_SCHEDULE, estado(atividade))
        assertEquals("linha órfã na fila", 0, h.orfaos())
    }
}
