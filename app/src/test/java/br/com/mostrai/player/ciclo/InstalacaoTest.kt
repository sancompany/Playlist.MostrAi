package br.com.mostrai.player.ciclo

import android.content.Context
import br.com.mostrai.player.BuildConfig
import br.com.mostrai.player.PlayerActivity
import br.com.mostrai.player.cache.ServidorDeTeste
import br.com.mostrai.player.update.Atualizador
import br.com.mostrai.player.update.EstadoUpdate
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

/**
 * Ciclo 4 — o ciclo de exibição depois de pedir a instalação.
 *
 * `concluirExibicao` para o ciclo quando abre o pedido de instalação e conta
 * com o diálogo do sistema cobrindo a Activity (onStop → onStart retoma).
 * Quando isso não acontece — a sessão falha sem diálogo, ou o diálogo é
 * translúcido e só causa onPause — alguém precisa retomar.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class InstalacaoTest {

    private lateinit var h: Harness

    @Before
    fun preparar() {
        h = Harness()
        File(h.contexto.cacheDir, "update").deleteRecursively()
    }

    @After
    fun encerrar() {
        File(h.contexto.cacheDir, "update").deleteRecursively()
        h.encerrar()
    }

    private fun geracao(atividade: PlayerActivity): Int {
        val campo = PlayerActivity::class.java.getDeclaredField("geracaoReproducao")
        campo.isAccessible = true
        return campo.getInt(atividade)
    }

    /**
     * Simula o STATE_ENDED de verdade: o player para de emitir eventos. Sem
     * isto o ExoPlayer do Robolectric, que não decodifica os bytes de teste,
     * acabaria em onPlayerError e reiniciaria o ciclo por conta própria.
     */
    private fun concluirExibicao(atividade: PlayerActivity) {
        val campo = PlayerActivity::class.java.getDeclaredField("player")
        campo.isAccessible = true
        (campo.get(atividade) as androidx.media3.common.Player?)?.let {
            it.clearMediaItems()
            it.stop()
        }
        h.idle()
        val metodo = PlayerActivity::class.java.getDeclaredMethod("concluirExibicao")
        metodo.isAccessible = true
        metodo.invoke(atividade)
    }

    private fun atualizacaoPronta() {
        val build = BuildConfig.VERSION_CODE + 1
        h.contexto.getSharedPreferences(Atualizador.ARQUIVO_PREFS, Context.MODE_PRIVATE).edit().clear()
            .putString("estado", EstadoUpdate.READY.name)
            .putInt("build_alvo", build)
            .commit()
        File(h.contexto.cacheDir, "update").apply { mkdirs() }.resolve("$build.apk").writeBytes("apk".toByteArray())
        shadowOf(h.contexto.packageManager).setCanRequestPackageInstalls(true)
    }

    @Test
    fun `instalacao que falha sem abrir dialogo nao congela a tela`() {
        h.provisionar()
        h.servidor.rotas["/playlist"] = ServidorDeTeste.Resposta(corpo = h.playlistComUmVideo().toByteArray())
        h.servidor.rotas["/midia"] = ServidorDeTeste.Resposta(corpo = "bytes".toByteArray())
        // Backend V1: sem heartbeat V2, ninguém manda `update` nulo que
        // limparia a atualização pronta — e sem janela, nada reposiciona.
        h.servidor.rotas["/player"] = ServidorDeTeste.Resposta(codigo = 404)
        atualizacaoPronta()

        val controle = h.subir()
        val atividade = controle.get()
        h.esperar { h.orfaos() == 1 } // exibição em andamento

        concluirExibicao(atividade)
        // O pedido saiu (o Robolectric pode já ter entregue a falha ao
        // receptor, que adia: DEFERRED).
        val estado = Atualizador(h.contexto, br.com.mostrai.player.estado.DiarioBordo(h.contexto)).estado
        assertTrue("instalação não foi pedida: $estado", estado == EstadoUpdate.INSTALL_REQUESTED || estado == EstadoUpdate.DEFERRED)

        // O sistema recusa a sessão: nenhum diálogo, a Activity nunca sai de
        // RESUMED.
        val instalador = h.contexto.packageManager.packageInstaller
        instalador.allSessions.forEach { shadowOf(instalador).setSessionFails(it.sessionId) }
        h.idle()

        val parado = geracao(atividade)
        h.avancar(2 * 60_000L)
        h.esperar { true }

        assertTrue("ciclo de exibição não retomou depois da falha da instalação", geracao(atividade) > parado)
    }

    @Test
    fun `dialogo translucido fechado retoma o ciclo`() {
        h.provisionar()
        h.servidor.rotas["/playlist"] = ServidorDeTeste.Resposta(corpo = h.playlistComUmVideo().toByteArray())
        h.servidor.rotas["/midia"] = ServidorDeTeste.Resposta(corpo = "bytes".toByteArray())
        // Backend V1: sem heartbeat V2, ninguém manda `update` nulo que
        // limparia a atualização pronta — e sem janela, nada reposiciona.
        h.servidor.rotas["/player"] = ServidorDeTeste.Resposta(codigo = 404)
        atualizacaoPronta()

        val controle = h.subir()
        val atividade = controle.get()
        h.esperar { geracao(atividade) >= 1 && h.servidor.contar("/midia") > 0 }

        concluirExibicao(atividade)
        // Diálogo com tema de diálogo: só pausa a Activity de trás.
        controle.pause()
        h.idle()
        val parado = geracao(atividade)
        controle.resume()
        h.esperar { true }

        assertTrue("ciclo não retomou ao fechar o diálogo", geracao(atividade) > parado)
    }
}
