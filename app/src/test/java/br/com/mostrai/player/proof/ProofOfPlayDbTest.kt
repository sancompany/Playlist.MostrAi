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
    fun `proximoADescartar devolve o de menor criadoEmMs quando todos sao iguais em valor`() {
        db.inserir(evento("b").copy(criadoEmMs = 5_000))
        db.inserir(evento("a").copy(criadoEmMs = 1_000))

        assertEquals("a", db.proximoADescartar())
    }

    @Test
    fun `fila vazia nao tem nada a descartar`() {
        assertNull(db.proximoADescartar())
    }

    // ------------------------------------------------------------------- R2

    @Test
    fun `descarta orfao antes de comprovante, mesmo que o comprovante seja mais antigo`() {
        // O comprovante é MAIS velho — pela regra anterior (só criado_em_ms)
        // ele sairia primeiro, e era esse o defeito: jogava fora receita
        // faturável e mantinha uma linha que nunca seria enviada.
        db.inserir(evento("comprovante").copy(criadoEmMs = 1_000, terminadoEm = "2026-01-01T00:00:00Z"))
        db.inserir(evento("orfao").copy(criadoEmMs = 9_000, terminadoEm = null))

        assertEquals("orfao", db.proximoADescartar())
    }

    @Test
    fun `descarta quarentena antes de comprovante`() {
        db.inserir(evento("comprovante").copy(criadoEmMs = 1_000, terminadoEm = "2026-01-01T00:00:00Z"))
        db.inserir(evento("ruim").copy(criadoEmMs = 9_000, terminadoEm = "2026-01-01T00:00:00Z"))
        db.marcarQuarentena("ruim", "rejeitado")

        assertEquals("ruim", db.proximoADescartar())
    }

    @Test
    fun `so descarta comprovante quando nao ha mais nada`() {
        db.inserir(evento("velho").copy(criadoEmMs = 1_000, terminadoEm = "2026-01-01T00:00:00Z"))
        db.inserir(evento("novo").copy(criadoEmMs = 9_000, terminadoEm = "2026-01-01T00:00:00Z"))

        assertEquals("velho", db.proximoADescartar())
    }

    // ------------------------------------------------------------------- R4

    @Test
    fun `evento em quarentena sai da fila de envio mas continua contado`() {
        db.inserir(evento("a").copy(terminadoEm = "2026-01-01T00:00:00Z"))
        db.marcarQuarentena("a", "rejeitado pelo servidor (400)")

        assertEquals(0, db.elegiveisParaEnvio(agoraMs = Long.MAX_VALUE, limite = 10).size)
        assertEquals(1, db.contarQuarentena())
        assertEquals(1, db.contarPendentes())
        assertEquals(0, db.contarAguardandoEnvio())
    }

    @Test
    fun `contarAguardandoEnvio ignora orfaos`() {
        db.inserir(evento("terminado").copy(terminadoEm = "2026-01-01T00:00:00Z"))
        db.inserir(evento("orfao"))

        assertEquals(2, db.contarPendentes())
        assertEquals(1, db.contarAguardandoEnvio())
    }

    @Test
    fun `maisAntigoAguardandoEnvio considera so o que pode ser enviado`() {
        db.inserir(evento("orfao").copy(criadoEmMs = 1_000))
        db.inserir(evento("bom").copy(criadoEmMs = 7_000, terminadoEm = "2026-01-01T00:00:00Z"))

        assertEquals(7_000L, db.maisAntigoAguardandoEnvioMs())
    }
}
