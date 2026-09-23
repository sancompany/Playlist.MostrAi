package br.com.mostrai.player.ciclo

import android.content.Context
import android.os.SystemClock
import br.com.mostrai.player.PlayerActivity
import br.com.mostrai.player.cache.ServidorDeTeste
import br.com.mostrai.player.kiosk.Watchdog
import java.util.concurrent.CountDownLatch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.LooperMode

/** Ciclo 1 — lifecycle do orquestrador, contra servidor e SQLite de verdade. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class CicloDeVidaTest {

    private lateinit var h: Harness

    @Before
    fun preparar() {
        h = Harness()
    }

    @After
    fun encerrar() = h.encerrar()

    private fun geracao(atividade: PlayerActivity): Int {
        val campo = PlayerActivity::class.java.getDeclaredField("geracaoReproducao")
        campo.isAccessible = true
        return campo.getInt(atividade)
    }

    private fun vivoEm(): Long =
        h.contexto.getSharedPreferences("mostrai_watchdog", Context.MODE_PRIVATE).getLong("vivo_em", 0L)

    // ------------------------------------------------------------ BUG-003

    @Test
    fun `player rodando normalmente nao e dado como morto pelo watchdog`() {
        // Em operação normal a Activity fica RESUMED por dias sem nenhum
        // callback de lifecycle. Se o sinal de vida só for renovado em
        // onStart/onResume, depois de 5 min o watchdog conclui que o player
        // sumiu e o "reabre" — o que fecha o painel de manutenção de quem
        // estiver nele e esconde o diálogo de instalação do OTA.
        h.subir()

        h.avancar(10 * 60_000L)

        val decisao = Watchdog.decidir(vivoEm(), SystemClock.elapsedRealtime(), tentativas = 0)
        assertFalse("watchdog reabriria um player saudável", decisao.abrirPlayer)
    }

    // ------------------------------------------------------------ BUG-001

    @Test
    fun `parar a activity com video resolvendo nao deixa linha orfa na fila`() {
        h.provisionar()
        h.servidor.rotas["/playlist"] = ServidorDeTeste.Resposta(corpo = h.playlistComUmVideo().toByteArray())
        val midia = CountDownLatch(1)
        h.servidor.travas["/midia"] = midia
        h.servidor.rotas["/midia"] = ServidorDeTeste.Resposta(corpo = "bytes".toByteArray())

        val controle = h.subir()
        // registrarInicio já gravou a linha; o download está preso.
        h.esperar { h.orfaos() == 1 }

        controle.pause().stop()
        midia.countDown()
        repeat(25) {
            Thread.sleep(20)
            h.idle()
        }

        assertEquals("exibição abandonada ficou órfã na fila", 0, h.orfaos())
    }

    // ------------------------------------------------------------ BUG-002

    @Test
    fun `voltar ao primeiro plano com busca em voo ainda reinicia a exibicao`() {
        h.provisionar()
        h.servidor.rotas["/playlist"] = ServidorDeTeste.Resposta(corpo = h.playlistComUmVideo().toByteArray())
        // Mídia presa: o item nunca chega ao ExoPlayer, o que isola o teste
        // do comportamento de decoder (inexistente no Robolectric).
        h.servidor.travas["/midia"] = CountDownLatch(1)

        val controle = h.subir()
        val atividade = controle.get()
        h.esperar { geracao(atividade) >= 1 }

        // Uma busca NÃO forçada fica em voo (o poll periódico de 15 min).
        val playlist = CountDownLatch(1)
        h.servidor.travas["/playlist"] = playlist
        h.avancar(15 * 60_000L + 1_000L)
        h.esperar { h.servidor.contar("/playlist") >= 2 }

        // Sai e volta: onStart pede uma busca forçada enquanto a outra ainda
        // não voltou.
        controle.pause().stop()
        controle.start().resume()
        h.idle()
        val depoisDeVoltar = geracao(atividade)

        playlist.countDown()
        h.esperar(timeoutMs = 5_000) { geracao(atividade) > depoisDeVoltar }

        assertTrue(geracao(atividade) > depoisDeVoltar)
    }

    // ------------------------------------------------------------ ROB-007

    @Test
    fun `hello que falhou no boot e tentado de novo sem reiniciar o app`() {
        // TV que liga sem internet e fica semanas no ar: sem nova tentativa,
        // o admin nunca recebe modelo, versão e resolução da tela.
        h.provisionar()
        h.servidor.rotas["/player"] = ServidorDeTeste.Resposta(codigo = 503)
        h.subir()
        h.esperar { h.servidor.contar("/player/tela-1/hello") == 1 }

        h.servidor.rotas["/player"] = ServidorDeTeste.Resposta(corpo = "{}".toByteArray())
        h.avancar(5 * 60_000L + 1_000L) // um heartbeat periódico
        runCatching { h.esperar { h.servidor.contar("/player/tela-1/hello") == 2 } }.onFailure { throw AssertionError("recebidas: ${h.servidor.recebidas}") }
    }

    // ------------------------------------------------------ Ciclo 18/19

    private fun execucaoAtual(atividade: PlayerActivity): String? {
        val campo = PlayerActivity::class.java.getDeclaredField("execucaoAtualId")
        campo.isAccessible = true
        return campo.get(atividade) as String?
    }

    @Test
    fun `parar com anuncio tocando nao deixa comprovante orfao`() {
        // A exibição que o onStop interrompe nunca vai terminar: sem
        // cancelar, a linha fica 7 dias ocupando a fila (mutação M13).
        h.provisionar()
        h.servidor.rotas["/playlist"] = ServidorDeTeste.Resposta(corpo = h.playlistComUmVideo().toByteArray())
        h.servidor.rotas["/midia"] = ServidorDeTeste.Resposta(corpo = "bytes".toByteArray())
        h.servidor.rotas["/player"] = ServidorDeTeste.Resposta(codigo = 404)

        val controle = h.subir()
        // O ExoPlayer do Robolectric não decodifica o corpo de teste e acaba
        // em onPlayerError; sob carga isso pode vir antes da observação, e a
        // tentativa seguinte só sai depois da pausa de 10s em tempo de
        // looper. Avança o relógio até pegar uma exibição no ar. Entre a
        // observação e o stop() nenhum callback é entregue: os dois rodam na
        // thread principal do teste.
        val limite = System.currentTimeMillis() + 20_000
        while (execucaoAtual(controle.get()) == null) {
            check(System.currentTimeMillis() < limite) { "nenhuma exibição chegou a tocar" }
            h.idle()
            Thread.sleep(20)
            if (execucaoAtual(controle.get()) == null) h.avancar(1_000L)
        }
        controle.pause().stop()

        h.esperar { h.orfaos() == 0 }
    }

    @Test
    fun `duas buscas de playlist nunca ficam em voo ao mesmo tempo`() {
        // Invariante 9: com uma busca por vez, a resposta de uma busca antiga
        // não tem como chegar depois da de uma nova e sobrescrevê-la.
        h.provisionar()
        h.servidor.rotas["/player"] = ServidorDeTeste.Resposta(codigo = 404)
        h.servidor.rotas["/playlist"] = ServidorDeTeste.Resposta(corpo = h.playlistComUmVideo().toByteArray())
        val trava = CountDownLatch(1)
        h.servidor.travas["/playlist"] = trava

        val atividade = h.subir().get()
        h.esperar { h.servidor.contar("/playlist") == 1 }
        val atualizar = PlayerActivity::class.java.getDeclaredMethod("atualizarPlaylist", Boolean::class.javaPrimitiveType)
        atualizar.isAccessible = true
        repeat(3) { atualizar.invoke(atividade, true) }
        repeat(10) {
            Thread.sleep(20)
            h.idle()
        }
        assertEquals("segunda busca saiu com a primeira em voo", 1, h.servidor.contar("/playlist"))

        trava.countDown()
        h.esperar { h.servidor.contar("/playlist") == 2 } // os três pedidos viram uma rodada só
        repeat(10) {
            Thread.sleep(20)
            h.idle()
        }
        assertEquals(2, h.servidor.contar("/playlist"))
    }

    @Test
    fun `rede que volta rebusca a playlist se a atual nao veio do servidor`() {
        // Queda atravessando a virada de hora: a busca da virada falha e a
        // TV segue na playlist em cache da janela anterior. Sem rebuscar ao
        // reconectar, até 15 min de anúncios da hora errada — comprovantes
        // que o servidor recusa como janela_expirada.
        h.provisionar()
        h.servidor.rotas["/player"] = ServidorDeTeste.Resposta(codigo = 404)
        h.servidor.rotas["/playlist"] = ServidorDeTeste.Resposta(codigo = 503)
        h.subir()
        h.esperar { h.servidor.contar("/playlist") == 1 }
        repeat(10) {
            Thread.sleep(20)
            h.idle()
        }

        h.servidor.rotas["/playlist"] = ServidorDeTeste.Resposta(corpo = h.playlistComUmVideo().toByteArray())
        val conectividade = h.contexto.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        val rede = conectividade.activeNetwork ?: org.robolectric.shadows.ShadowNetwork.newInstance(1)
        org.robolectric.Shadows.shadowOf(conectividade).networkCallbacks.forEach { it.onAvailable(rede) }

        h.esperar { h.servidor.contar("/playlist") == 2 }
    }

    @Test
    fun `rede disponivel no boot nao gera busca extra de playlist`() {
        h.provisionar()
        h.servidor.rotas["/player"] = ServidorDeTeste.Resposta(codigo = 404)
        h.servidor.rotas["/playlist"] = ServidorDeTeste.Resposta(corpo = h.playlistComUmVideo().toByteArray())
        h.subir()
        h.esperar { h.servidor.contar("/playlist") == 1 }
        val conectividade = h.contexto.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        val rede = conectividade.activeNetwork ?: org.robolectric.shadows.ShadowNetwork.newInstance(1)
        org.robolectric.Shadows.shadowOf(conectividade).networkCallbacks.forEach { it.onAvailable(rede) }
        repeat(25) {
            Thread.sleep(20)
            h.idle()
        }

        assertEquals(1, h.servidor.contar("/playlist"))
    }
}
