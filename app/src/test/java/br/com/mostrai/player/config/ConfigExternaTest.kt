package br.com.mostrai.player.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigExternaTest {

    @Test
    fun `le todos os campos do json`() {
        val dados = ConfigExterna.parse(
            """
            {
                "dispositivoId": "tv-01",
                "chaveAparelho": "chave-abc",
                "baseUrl": "https://exemplo.com/api",
                "pin": "1357",
                "margemVminTopo": 2.5,
                "margemVminBase": 1.5,
                "margemVminEsquerda": 3,
                "margemVminDireita": 0.5,
                "rotacaoTela": 90
            }
            """.trimIndent()
        )

        assertEquals("tv-01", dados?.dispositivoId)
        assertEquals("chave-abc", dados?.chaveAparelho)
        assertEquals("https://exemplo.com/api", dados?.baseUrl)
        assertEquals("1357", dados?.pin)
        assertEquals(2.5f, dados?.margemVminTopo)
        assertEquals(1.5f, dados?.margemVminBase)
        assertEquals(3f, dados?.margemVminEsquerda)
        assertEquals(0.5f, dados?.margemVminDireita)
        assertEquals(90, dados?.rotacaoTela)
    }

    @Test
    fun `campo ausente vira nulo, nunca string vazia`() {
        val dados = ConfigExterna.parse("""{"dispositivoId": "tv-01"}""")

        assertEquals("tv-01", dados?.dispositivoId)
        assertNull(dados?.chaveAparelho)
        assertNull(dados?.baseUrl)
        assertNull(dados?.pin)
        assertNull(dados?.margemVminTopo)
        assertNull(dados?.margemVminBase)
        assertNull(dados?.margemVminEsquerda)
        assertNull(dados?.margemVminDireita)
    }

    @Test
    fun `campo vazio tambem vira nulo`() {
        val dados = ConfigExterna.parse("""{"dispositivoId": "", "chaveAparelho": "chave"}""")

        assertNull(dados?.dispositivoId)
        assertEquals("chave", dados?.chaveAparelho)
    }

    @Test
    fun `json invalido devolve nulo, nao lanca excecao`() {
        assertNull(ConfigExterna.parse("isto nao e json"))
        assertNull(ConfigExterna.parse(""))
    }

    @Test
    fun `margemVmin nao numerico vira nulo, nunca NaN`() {
        val dados = ConfigExterna.parse("""{"margemVminTopo": "isto nao e numero"}""")
        assertNull(dados?.margemVminTopo)
    }

    @Test
    fun `objeto vazio devolve dados todos nulos`() {
        val dados = ConfigExterna.parse("{}")

        assertEquals(ConfigExterna.Dados(), dados)
    }

    @Test
    fun `rotacaoTela fora do conjunto valido vira nulo, nunca gira a esmo`() {
        assertNull(ConfigExterna.parse("""{"rotacaoTela": 45}""")?.rotacaoTela)
        assertNull(ConfigExterna.parse("""{"rotacaoTela": -90}""")?.rotacaoTela)
    }

    @Test
    fun `rotacaoTela aceita os quatro valores validos`() {
        for (valor in listOf(0, 90, 180, 270)) {
            assertEquals(valor, ConfigExterna.parse("""{"rotacaoTela": $valor}""")?.rotacaoTela)
        }
    }

    @Test
    fun `espaco colado junto com id, chave, token e url e descartado`() {
        // Auditoria H: valor copiado e colado com espaço ou quebra de linha
        // virava URL inválida ou header de credencial errado — a TV parecia
        // provisionada e nunca autenticava.
        val dados = ConfigExterna.parse(
            """{"dispositivoId": " tela-1 ", "chaveAparelho": "k1\n", "tokenProvisionamento": " tok ",
               "baseUrl": " https://api.exemplo.com ", "pin": " 1234 "}""",
        )!!

        assertEquals("tela-1", dados.dispositivoId)
        assertEquals("k1", dados.chaveAparelho)
        assertEquals("tok", dados.tokenProvisionamento)
        assertEquals("https://api.exemplo.com", dados.baseUrl)
        assertEquals("1234", dados.pin)
    }
}
