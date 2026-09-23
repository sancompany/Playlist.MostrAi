package br.com.mostrai.player.ciclo

import android.content.Intent
import br.com.mostrai.player.PlayerActivity
import br.com.mostrai.player.cache.ServidorDeTeste
import br.com.mostrai.player.config.ConfigAparelho
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.LooperMode

/**
 * Ciclo 7/15 — o Intent que abre o player vem de qualquer app instalado.
 *
 * `PlayerActivity` é exportada (LAUNCHER e HOME). Os extras de
 * provisionamento existem para a bancada (`adb shell am start ... --es`),
 * mas o Android não diz quem mandou o Intent.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SegurancaLocalTest {

    @Test
    fun `em release, intent de outro app nao troca o servidor de uma tela provisionada`() {
        // Trocar baseUrl de uma tela em operação manda a chave do aparelho,
        // no header da próxima requisição, para o servidor de quem trocou —
        // e passa a decidir o que a tela da loja exibe.
        assertFalse(PlayerActivity.aceitaExtrasDeProvisionamento(ehDepuracao = false, provisionado = true))
    }

    @Test
    fun `em release, aparelho novo ainda aceita provisionamento de bancada`() {
        assertTrue(PlayerActivity.aceitaExtrasDeProvisionamento(ehDepuracao = false, provisionado = false))
    }

    @Test
    fun `em depuracao, extras continuam sobrescrevendo`() {
        assertTrue(PlayerActivity.aceitaExtrasDeProvisionamento(ehDepuracao = true, provisionado = true))
    }

    @Test
    fun `token recebido depois do boot e trocado na hora, nao no proximo heartbeat`() {
        // Bancada (onNewIntent) ou permissão de armazenamento concedida
        // depois do boot: o token chega com o ciclo já rodando. Esperar o
        // heartbeat periódico deixava a TV até 5 min na tela "não
        // provisionado" com tudo pronto para funcionar.
        val h = Harness()
        try {
            ConfigAparelho(h.contexto).baseUrl = h.servidor.baseUrl
            h.servidor.rotas["/player"] = ServidorDeTeste.Resposta(codigo = 404)
            val controle = h.subir()

            controle.newIntent(
                Intent(h.contexto, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_TOKEN, "tok_1"),
            )
            h.esperar(timeoutMs = 3_000) { h.servidor.contar("/player/provisionar") > 0 }
        } finally {
            h.encerrar()
        }
    }

    @Test
    fun `tecla voltar do controle nao fecha o player`() {
        // Um VOLTAR no controle da loja encerrava a Activity no meio do
        // anúncio pago; sem o Mostraí como HOME padrão, a tela ia para o
        // launcher da TV até o watchdog reabrir, 5–7 min depois. O técnico
        // continua saindo pelas teclas HOME e de configurações.
        val h = Harness()
        try {
            val atividade = h.subir().get()

            atividade.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
            atividade.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
            h.idle()

            assertFalse("VOLTAR fechou o player", atividade.isFinishing)
        } finally {
            h.encerrar()
        }
    }
}
