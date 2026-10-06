package br.com.mostrai.player.atualizacao

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

/** Quando um pendrive volta a ser lido e oferecido. */
@RunWith(RobolectricTestRunner::class)
class EstadoAtualizacaoTest {

    private val contexto: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun preparar() {
        EstadoAtualizacao.reiniciar()
        contexto.getSharedPreferences("mostrai_atualizacao", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `pendrive novo e verificado uma vez so`() {
        assertEquals(setOf("A"), EstadoAtualizacao.registrarMontados(setOf("A")))
        EstadoAtualizacao.marcarVerificado("A")
        assertEquals(emptySet<String>(), EstadoAtualizacao.registrarMontados(setOf("A")))
        assertEquals("só o novo", setOf("B"), EstadoAtualizacao.registrarMontados(setOf("A", "B")))
        assertEquals(setOf("A", "B"), EstadoAtualizacao.conectados)
    }

    @Test
    fun `Depois vale ate o pendrive sair - reconectado volta a ser oferecido`() {
        EstadoAtualizacao.registrarMontados(setOf("A"))
        EstadoAtualizacao.marcarVerificado("A")
        EstadoAtualizacao.dispensar("A")
        assertTrue(EstadoAtualizacao.foiDispensado("A"))
        assertEquals(emptySet<String>(), EstadoAtualizacao.registrarMontados(setOf("A")))

        EstadoAtualizacao.registrarMontados(emptySet())
        assertFalse(EstadoAtualizacao.foiDispensado("A"))
        assertEquals(setOf("A"), EstadoAtualizacao.registrarMontados(setOf("A")))
    }

    @Test
    fun `Verificar USB do tecnico le e oferece de novo o que esta montado`() {
        EstadoAtualizacao.registrarMontados(setOf("A"))
        EstadoAtualizacao.marcarVerificado("A")
        EstadoAtualizacao.dispensar("A")
        EstadoAtualizacao.esquecerVerificacoes()
        assertFalse(EstadoAtualizacao.foiDispensado("A"))
        assertEquals(setOf("A"), EstadoAtualizacao.registrarMontados(setOf("A")))
    }

    @Test
    fun `instalacao iniciada que nao entrou e percebida uma vez`() {
        EstadoAtualizacao.marcarInstalacaoIniciada(contexto, 7)
        assertTrue("voltou ainda na 6", EstadoAtualizacao.instalacaoNaoConcluida(contexto, 6))
        assertFalse("só uma vez", EstadoAtualizacao.instalacaoNaoConcluida(contexto, 6))
    }

    @Test
    fun `instalacao que entrou nao vira aviso de falha`() {
        EstadoAtualizacao.marcarInstalacaoIniciada(contexto, 7)
        assertFalse(EstadoAtualizacao.instalacaoNaoConcluida(contexto, 7))
        EstadoAtualizacao.marcarInstalacaoIniciada(contexto, 7)
        EstadoAtualizacao.registrarAtualizado(contexto, "3.0.2 (7)")
        assertFalse(EstadoAtualizacao.instalacaoNaoConcluida(contexto, 6))
        assertEquals("3.0.2 (7)", EstadoAtualizacao.ultimaAtualizacao(contexto))
        assertNull(EstadoAtualizacao.candidato)
    }
}
