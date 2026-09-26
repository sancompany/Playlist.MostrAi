package br.com.mostrai.player.provisionamento

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import br.com.mostrai.player.config.ConfigAparelho
import br.com.mostrai.player.estado.DiarioBordo
import br.com.mostrai.player.network.MostraiApi
import br.com.mostrai.player.network.MostraiApi.Provisionamento
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Troca ID + código pela credencial (contrato §3). */
@RunWith(RobolectricTestRunner::class)
class ProvisionadorTest {

    private class ApiRoteirizada(config: ConfigAparelho) : MostraiApi(config) {
        val respostas = ArrayDeque<Provisionamento>()
        val enviados = mutableListOf<Pair<String, String>>()

        override fun provisionar(codigoTela: String, codigoInstalacao: String): Provisionamento {
            enviados += codigoTela to codigoInstalacao
            return respostas.removeFirstOrNull() ?: Provisionamento.Transitorio("sem roteiro")
        }
    }

    private lateinit var contexto: Context
    private lateinit var config: ConfigAparelho
    private lateinit var api: ApiRoteirizada
    private val esperas = mutableListOf<Long>()
    private lateinit var provisionador: Provisionador

    private val credencial = Provisionamento.Ok(MostraiApi.Credenciais("M-0235", "k".repeat(43)))

    @Before
    fun preparar() {
        contexto = ApplicationProvider.getApplicationContext()
        contexto.getSharedPreferences(ConfigAparelho.ARQUIVO, Context.MODE_PRIVATE).edit().clear().commit()
        contexto.deleteDatabase(DiarioBordo.NOME_ARQUIVO)
        config = ConfigAparelho(contexto)
        api = ApiRoteirizada(config)
        provisionador = Provisionador(config, api, DiarioBordo(contexto)) { esperas += it }
    }

    @Test
    fun `200 grava ID e chave e sobrevive a reiniciar o app`() {
        api.respostas += credencial

        assertEquals(Provisionador.Resultado.Ok, provisionador.provisionar("235", "7k4m9q2w"))

        assertEquals("M-0235" to "7K4M-9Q2W", api.enviados.single())
        val depois = ConfigAparelho(contexto)
        assertTrue(depois.provisionado)
        assertEquals("M-0235", depois.dispositivoId)
        assertEquals("k".repeat(43), depois.chaveAparelho)
    }

    @Test
    fun `formato invalido nao chega ao servidor`() {
        assertEquals(Provisionador.Resultado.IdInvalido, provisionador.provisionar("M-", "7K4M9Q2W"))
        assertEquals(Provisionador.Resultado.CodigoInvalido, provisionador.provisionar("235", "7K4M"))
        assertTrue(api.enviados.isEmpty())
    }

    @Test
    fun `400 volta na hora para o operador conferir`() {
        api.respostas += Provisionamento.Invalido
        assertEquals(Provisionador.Resultado.Invalido, provisionador.provisionar("235", "7K4M9Q2W"))
        assertEquals(1, api.enviados.size)
        assertFalse(config.provisionado)
    }

    @Test
    fun `401 nunca e tentado de novo sozinho`() {
        api.respostas += Provisionamento.Recusado
        api.respostas += credencial // não pode ser consumida

        assertEquals(Provisionador.Resultado.Recusado, provisionador.provisionar("235", "7K4M9Q2W"))
        assertEquals(1, api.enviados.size)
        assertTrue(esperas.isEmpty())
        assertFalse(config.provisionado)
    }

    @Test
    fun `429 devolve o Retry-After sem insistir`() {
        api.respostas += Provisionamento.Limitado(42)
        assertEquals(Provisionador.Resultado.Limitado(42), provisionador.provisionar("235", "7K4M9Q2W"))
        assertEquals(1, api.enviados.size)
    }

    @Test
    fun `resposta 200 perdida e repetida com o MESMO par, dentro de 5 minutos`() {
        // Contrato §3: reenviar o mesmo ID + código em até 5 min devolve a
        // mesma credencial. Rede caída e 5xx são exatamente esse caso.
        api.respostas += Provisionamento.Transitorio("timeout")
        api.respostas += Provisionamento.Transitorio("HTTP 502")
        api.respostas += credencial

        assertEquals(Provisionador.Resultado.Ok, provisionador.provisionar("M-0235", "7K4M-9Q2W"))

        assertEquals(3, api.enviados.size)
        assertEquals(1, api.enviados.toSet().size)
        assertEquals(listOf(2_000L, 5_000L), esperas)
        assertTrue(config.provisionado)
    }

    @Test
    fun `repeticao curta nunca passa da janela de 5 minutos`() {
        assertTrue(Provisionador.ESPERAS_MS.sum() < 5 * 60_000L)

        assertEquals(Provisionador.Resultado.SemConexao, provisionador.provisionar("235", "7K4M9Q2W"))
        assertEquals(Provisionador.ESPERAS_MS.size + 1, api.enviados.size)
        assertFalse(config.provisionado)
    }

    @Test
    fun `reprovisionar substitui a credencial antiga de uma vez`() {
        config.gravarCredenciais("M-0235", "antiga")
        config.esquecerCredencialSeFor("antiga")
        api.respostas += Provisionamento.Ok(MostraiApi.Credenciais("M-0235", "nova"))

        provisionador.provisionar("235", "7K4M9Q2W")

        assertEquals("nova", config.chaveAparelho)
    }

    @Test
    fun `o codigo de instalacao nunca vai para o disco`() {
        api.respostas += credencial
        provisionador.provisionar("235", "7K4M9Q2W")

        val prefs = contexto.getSharedPreferences(ConfigAparelho.ARQUIVO, Context.MODE_PRIVATE)
        assertNull(prefs.all.values.firstOrNull { it.toString().contains("7K4M") })
        val diario = DiarioBordo(contexto).ultimos(50)
        assertNull(diario.firstOrNull { (it.mensagem ?: "").contains("7K4M") || (it.mensagem ?: "").contains("kkkk") })
    }
}
