package br.com.mostrai.player.ciclo

import br.com.mostrai.player.PlayerActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Ciclo 7/15 — o Intent que abre o player vem de qualquer app instalado.
 *
 * `PlayerActivity` é exportada (LAUNCHER e HOME). Os extras de
 * provisionamento existem para a bancada (`adb shell am start ... --es`),
 * mas o Android não diz quem mandou o Intent.
 */
@RunWith(RobolectricTestRunner::class)
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
}
