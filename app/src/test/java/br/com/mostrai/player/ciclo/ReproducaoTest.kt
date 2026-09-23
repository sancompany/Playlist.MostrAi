package br.com.mostrai.player.ciclo

import br.com.mostrai.player.PlayerActivity
import br.com.mostrai.player.cache.ServidorDeTeste
import br.com.mostrai.player.estado.DiarioBordo
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.LooperMode

/** Ciclo 4 — o laço de exibição quando nada consegue tocar. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ReproducaoTest {

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

    @Test
    fun `playlist inteira falhando nao vira laco apertado`() {
        // Todo item rejeitado de imediato (hash divergente em silêncio, ou
        // arquivo corrompido que o decoder recusa na hora): cada falha chama
        // avancar() na mesma hora. Sem pausa, a TV gira o laço o mais rápido
        // que a thread de fundo deixa — CPU cheia e uma escrita SQLite por
        // volta na fila e no diário, o dia inteiro, na flash da TV.
        h.provisionar()
        h.servidor.rotas["/playlist"] = ServidorDeTeste.Resposta(
            corpo = h.playlistComUmVideo(contentHash = "b".repeat(64)).toByteArray(),
        )
        h.servidor.rotas["/midia"] = ServidorDeTeste.Resposta(corpo = "nao-confere".toByteArray())

        val atividade = h.subir().get()
        h.esperar { h.servidor.contar("/midia") > 0 }
        val inicio = geracao(atividade)

        // 2 s de relógio real alternando o looper — o laço, se existir, gira.
        val limite = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < limite) {
            h.idle()
            Thread.sleep(10)
        }
        val voltas = geracao(atividade) - inicio

        val falhas = DiarioBordo(h.contexto).ultimos(DiarioBordo.MAXIMO_EVENTOS)
            .count { it.codigo == DiarioBordo.Codigo.MIDIA_HASH_DIVERGENTE.name }
        assertTrue("laço deu $voltas voltas em 2s sem avançar o relógio ($falhas falhas no diário)", voltas <= 2)
        // E não desiste: passada a pausa, tenta de novo (o backend pode ter
        // corrigido o arquivo).
        val antes = geracao(atividade)
        h.avancar(10_000L) // PlayerActivity.ESPERA_APOS_VOLTA_SEM_EXIBICAO_MS
        h.esperar { geracao(atividade) > antes }
    }
}
