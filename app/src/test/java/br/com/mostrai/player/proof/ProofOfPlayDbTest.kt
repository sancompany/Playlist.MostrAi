package br.com.mostrai.player.proof

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Testa a fila durável contra um SQLite de verdade (via Robolectric) — o
 * coração do projeto (decisão 3 e seção 6.4). Sem Robolectric, um teste
 * aqui só provaria que o Kotlin compila, não que a durabilidade funciona.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ProofOfPlayDbTest {

    private lateinit var db: ProofOfPlayDb

    @Before
    fun setUp() {
        db = ProofOfPlayDb(ApplicationProvider.getApplicationContext())
    }

    private fun evento(id: String, terminadoEm: String? = null) = EventoExibicao(
        execucaoId = id,
        janelaId = "janela-1",
        itemProgramacaoId = "slot-1",
        criativoId = "crv-1",
        anuncianteId = "anun-1",
        formatoLegado = false,
        iniciadoEm = "2026-09-21T13:00:00-03:00",
        terminadoEm = terminadoEm,
        tentativas = 0,
        proximoEnvioElegivelEm = 0L,
        criadoEmMs = System.currentTimeMillis(),
    )

    @Test
    fun `linha recem inserida sem terminadoEm nao e elegivel para envio`() {
        db.inserir(evento("e1"))

        assertEquals(1, db.contarPendentes())
        assertTrue(db.elegiveisParaEnvio(System.currentTimeMillis(), 50).isEmpty())
    }

    @Test
    fun `marcarTerminado torna a linha elegivel`() {
        db.inserir(evento("e2"))
        db.marcarTerminado("e2", "2026-09-21T13:00:20-03:00")

        val elegiveis = db.elegiveisParaEnvio(System.currentTimeMillis(), 50)

        assertEquals(1, elegiveis.size)
        assertEquals("e2", elegiveis[0].execucaoId)
        assertEquals("2026-09-21T13:00:20-03:00", elegiveis[0].terminadoEm)
    }

    @Test
    fun `remover tira a linha definitivamente da fila`() {
        db.inserir(evento("e3"))
        db.remover("e3")

        assertEquals(0, db.contarPendentes())
    }

    @Test
    fun `adiarReenvio empurra a linha para fora da janela elegivel`() {
        db.inserir(evento("e4"))
        db.marcarTerminado("e4", "2026-09-21T13:00:20-03:00")

        db.adiarReenvio("e4", System.currentTimeMillis() + 60_000, tentativas = 1)

        assertTrue(db.elegiveisParaEnvio(System.currentTimeMillis(), 50).isEmpty())
        // mas continua na fila, só não elegível agora
        assertEquals(1, db.contarPendentes())
    }

    @Test
    fun `limite e ordem por criado_em_ms, mais antigo primeiro`() {
        db.inserir(evento("velho").copy(criadoEmMs = 1_000))
        db.marcarTerminado("velho", "2026-09-21T13:00:00-03:00")
        db.inserir(evento("novo").copy(criadoEmMs = 2_000))
        db.marcarTerminado("novo", "2026-09-21T13:00:00-03:00")

        val elegiveis = db.elegiveisParaEnvio(System.currentTimeMillis(), 1)

        assertEquals(1, elegiveis.size)
        assertEquals("velho", elegiveis[0].execucaoId)
    }

    @Test
    fun `removerExpirados so remove o que e mais velho que o limite`() {
        db.inserir(evento("antigo").copy(criadoEmMs = 1_000))
        db.inserir(evento("recente").copy(criadoEmMs = System.currentTimeMillis()))

        val removidos = db.removerExpirados(limiteMs = 500_000)

        assertEquals(1, removidos)
        assertEquals(1, db.contarPendentes())
    }

    @Test
    fun `maisAntigoNaoEnviado devolve o de menor criadoEmMs`() {
        db.inserir(evento("b").copy(criadoEmMs = 5_000))
        db.inserir(evento("a").copy(criadoEmMs = 1_000))

        assertEquals("a", db.maisAntigoNaoEnviado())
    }

    @Test
    fun `fila vazia nao tem mais antigo`() {
        assertNull(db.maisAntigoNaoEnviado())
    }
}
