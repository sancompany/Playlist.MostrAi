package br.com.mostrai.player.estado

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * O erro precisa sobreviver ao caminho crash → restart → heartbeat. Sem
 * isso, "Erro do player" no admin é um estado que nunca acende.
 */
@RunWith(RobolectricTestRunner::class)
class DiarioBordoTest {

    private lateinit var contexto: Context
    private lateinit var diario: DiarioBordo

    @Before
    fun preparar() {
        contexto = ApplicationProvider.getApplicationContext()
        contexto.deleteDatabase(DiarioBordo.NOME_ARQUIVO)
        diario = DiarioBordo(contexto)
    }

    @Test
    fun `sem nada registrado nao ha erro`() {
        assertNull(diario.ultimoErro())
    }

    @Test
    fun `evento de erro fica disponivel`() {
        diario.registrar(DiarioBordo.Codigo.PLAYBACK_FALHOU, "codec faltando")

        val erro = diario.ultimoErro()!!
        assertEquals("PLAYBACK_FALHOU", erro.codigo)
        assertEquals("codec faltando", erro.mensagem)
        assertEquals(DiarioBordo.Severidade.ERRO, erro.severidade)
    }

    @Test
    fun `evento informativo nao conta como erro`() {
        diario.registrar(DiarioBordo.Codigo.BOOT)
        diario.registrar(DiarioBordo.Codigo.CONFIG_APLICADA)

        assertNull(diario.ultimoErro())
        assertEquals(2, diario.ultimos(10).size)
    }

    @Test
    fun `o erro sobrevive a uma instancia nova, como depois de um crash`() {
        diario.registrar(DiarioBordo.Codigo.PLAYBACK_FALHOU, "morreu às 3h")

        // Processo reiniciou: instância nova, mesmo banco.
        val depoisDoRestart = DiarioBordo(contexto)

        assertEquals("PLAYBACK_FALHOU", depoisDoRestart.ultimoErro()?.codigo)
    }

    @Test
    fun `o erro mais recente vence`() {
        diario.registrar(DiarioBordo.Codigo.PLAYLIST_FALHOU, "primeiro")
        Thread.sleep(2)
        diario.registrar(DiarioBordo.Codigo.AUTH_FALHOU, "segundo")

        assertEquals("AUTH_FALHOU", diario.ultimoErro()?.codigo)
    }

    @Test
    fun `voltar ao normal limpa o erro`() {
        diario.registrar(DiarioBordo.Codigo.PLAYLIST_FALHOU, "sem rede")
        diario.limparErros()

        assertNull(diario.ultimoErro())
    }

    @Test
    fun `limpar erros preserva o historico informativo`() {
        diario.registrar(DiarioBordo.Codigo.BOOT)
        diario.registrar(DiarioBordo.Codigo.PLAYLIST_FALHOU, "sem rede")

        diario.limparErros()

        assertEquals(1, diario.ultimos(10).size)
        assertEquals("BOOT", diario.ultimos(10).first().codigo)
    }

    @Test
    fun `o diario nao cresce sem limite`() {
        repeat(DiarioBordo.MAXIMO_EVENTOS + 50) {
            diario.registrar(DiarioBordo.Codigo.BOOT, "evento $it")
        }

        assertTrue(diario.ultimos(1000).size <= DiarioBordo.MAXIMO_EVENTOS)
    }

    // ------------------------------------------------------------ sanitização

    @Test
    fun `mensagem nunca carrega segredo`() {
        val sujo = "falhou com chave=abc123 e token: xyz789"

        val limpo = DiarioBordo.sanitizar(sujo)

        assertFalse(limpo.contains("abc123"))
        assertFalse(limpo.contains("xyz789"))
    }

    @Test
    fun `mensagem e cortada, nao vira log gigante`() {
        val limpo = DiarioBordo.sanitizar("x".repeat(5000))

        assertTrue(limpo.length <= 200)
    }

    @Test
    fun `sanitizacao e aplicada ao registrar`() {
        diario.registrar(DiarioBordo.Codigo.AUTH_FALHOU, "recusou com chave=segredo-real")

        assertFalse(diario.ultimoErro()!!.mensagem!!.contains("segredo-real"))
    }

    @Test
    fun `todo codigo tem severidade coerente`() {
        assertEquals(DiarioBordo.Severidade.INFO, DiarioBordo.Codigo.BOOT.severidade)
        assertEquals(DiarioBordo.Severidade.ERRO, DiarioBordo.Codigo.PLAYBACK_FALHOU.severidade)
        assertEquals(DiarioBordo.Severidade.ERRO, DiarioBordo.Codigo.MIDIA_HASH_DIVERGENTE.severidade)
        assertEquals(DiarioBordo.Severidade.INFO, DiarioBordo.Codigo.CONFIG_APLICADA.severidade)
    }
}
