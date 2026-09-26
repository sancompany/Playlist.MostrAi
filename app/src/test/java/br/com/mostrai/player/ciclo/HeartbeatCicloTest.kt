package br.com.mostrai.player.ciclo

import android.widget.FrameLayout
import br.com.mostrai.player.R
import br.com.mostrai.player.cache.ServidorDeTeste
import br.com.mostrai.player.config.ConfigAparelho
import java.util.concurrent.CountDownLatch
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.LooperMode

/** Heartbeat de 15 s e config aplicada sem mexer na exibição (contrato §5 e §6). */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class HeartbeatCicloTest {

    private lateinit var h: Harness

    @Before
    fun preparar() {
        h = Harness()
        h.provisionar()
        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(200, h.playlistComUmVideo(duracao = 600))
        h.midiaNoAr()
    }

    @After
    fun encerrar() = h.encerrar()

    private fun heartbeats() = h.servidor.contar("/player/M-0001/heartbeat")

    @Test
    fun `heartbeat sai no boot e depois a cada 15 segundos`() {
        val atividade = h.subir().get()
        val emVoo = h.campo<java.util.concurrent.atomic.AtomicBoolean>(atividade, "heartbeatEmVoo")
        // O boot manda um; o registro do callback de rede pode mandar outro.
        h.esperar { heartbeats() >= 1 && !emVoo.get() }

        repeat(3) {
            val antes = heartbeats()
            h.avancar(15_000L)
            h.esperar { heartbeats() == antes + 1 && !emVoo.get() }
        }
    }

    @Test
    fun `corpo do heartbeat e o do contrato`() {
        val atividade = h.subir().get()
        h.esperar { h.campo<String?>(atividade, "execucaoAtualId") != null }
        h.avancar(15_000L)
        h.esperar { heartbeats() >= 2 }

        val corpo = JSONObject(h.servidor.ultima("/player/M-0001/heartbeat")!!.corpo)
        assertEquals(setOf("estado", "configVersionAplicada", "criativoId", "erro", "fila"), corpo.keys().asSequence().toSet())
        assertEquals("PLAYING", corpo.getString("estado"))
        assertEquals("c1", corpo.getString("criativoId"))
        assertEquals(0, corpo.getJSONObject("fila").getInt("pendentes"))
    }

    @Test
    fun `heartbeat preso nao trava a reproducao nem se empilha`() {
        val trava = CountDownLatch(1)
        h.servidor.travas["/player/M-0001/heartbeat"] = trava
        val atividade = h.subir().get()

        h.esperar { h.campo<String?>(atividade, "execucaoAtualId") != null }
        h.avancar(45_000L)
        h.idle()

        assertEquals("heartbeats empilhados com o primeiro preso", 1, heartbeats())
        trava.countDown()
    }

    @Test
    fun `playlist atualizar do heartbeat busca a playlist na hora`() {
        val atividade = h.subir().get()
        val emVoo = h.campo<java.util.concurrent.atomic.AtomicBoolean>(atividade, "heartbeatEmVoo")
        // Com o primeiro ainda em voo, o de 15 s seguintes é pulado de
        // propósito (nunca empilha) — e o teste esperaria mais 15 s que o
        // looper pausado não anda.
        h.esperar { h.servidor.contar("/playlist/") == 1 && heartbeats() >= 1 && !emVoo.get() }

        h.servidor.rotas["/player/"] = ServidorDeTeste.Resposta(200, """{"configVersion":0,"playlist":{"atualizar":true}}""")
        h.avancar(15_000L)

        h.esperar { h.servidor.contar("/playlist/") == 2 }
    }

    @Test
    fun `margem nova do admin entra sem reiniciar activity, item nem exibicao`() {
        val controle = h.subir()
        val atividade = controle.get()
        h.esperar { h.campo<String?>(atividade, "execucaoAtualId") != null }
        val execucao = h.campo<String?>(atividade, "execucaoAtualId")
        val player = h.campo<Any?>(atividade, "player")
        val rotor = h.vista<FrameLayout>(atividade, R.id.rotor)
        assertEquals(0, rotor.paddingTop)

        h.servidor.rotas["/player/M-0001/config"] =
            ServidorDeTeste.Resposta(200, """{"configVersion":2,"margens":{"superior":5,"direita":0,"inferior":0,"esquerda":0}}""")
        h.servidor.rotas["/player/"] = ServidorDeTeste.Resposta(200, """{"configVersion":2,"playlist":{"atualizar":false}}""")
        h.avancar(15_000L)
        h.esperar { rotor.paddingTop > 0 }

        assertEquals(2, ConfigAparelho(h.contexto).configVersionAplicada)
        assertSame(atividade, controle.get())
        assertSame("player recriado", player, h.campo<Any?>(atividade, "player"))
        assertEquals("exibição cortada pela margem", execucao, h.campo<String?>(atividade, "execucaoAtualId"))
        assertNotNull(execucao)
        assertTrue(h.orfaos() == 1) // a mesma exibição, ainda em andamento
    }
}
