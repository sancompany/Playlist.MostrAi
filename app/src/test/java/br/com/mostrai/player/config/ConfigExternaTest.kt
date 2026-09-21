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
                "margemVmin": 2.5,
                "rotacaoTela": 90
            }
            """.trimIndent()
        )

        assertEquals("tv-01", dados?.dispositivoId)
        assertEquals("chave-abc", dados?.chaveAparelho)
        assertEquals("https://exemplo.com/api", dados?.baseUrl)
        assertEquals("1357", dados?.pin)
        assertEquals(2.5f, dados?.margemVmin)
        assertEquals(90, dados?.rotacaoTela)
    }

    @Test
    fun `campo ausente vira nulo, nunca string vazia`() {
        val dados = ConfigExterna.parse("""{"dispositivoId": "tv-01"}""")

        assertEquals("tv-01", dados?.dispositivoId)
        assertNull(dados?.chaveAparelho)
        assertNull(dados?.baseUrl)
        assertNull(dados?.pin)
        assertNull(dados?.margemVmin)
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
        val dados = ConfigExterna.parse("""{"margemVmin": "isto nao e numero"}""")
        assertNull(dados?.margemVmin)
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
}
