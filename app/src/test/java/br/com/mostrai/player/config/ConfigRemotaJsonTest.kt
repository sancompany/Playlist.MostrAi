package br.com.mostrai.player.config

import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigRemotaJsonTest {

    @Test
    fun `le a config completa`() {
        val corpo = """
            {
              "configVersion": 184,
              "margens": {"superior": 2.5, "inferior": 1.5, "esquerda": 3, "direita": 0.5},
              "rotacaoTela": 90,
              "pinPainel": "4821",
              "versaoMinimaBuild": 2,
              "operacao": {
                "regime": "CUSTOM",
                "timezone": "America/Sao_Paulo",
                "porDiaDaSemana": {"seg": [{"inicio": "09:00", "fim": "18:00"}]}
              },
              "update": {"baixarAutomaticamente": false, "horasEntreTentativas": 12},
              "cache": {"tetoMegabytes": 2048}
            }
        """.trimIndent()

        val config = ConfigRemotaJson.parse(corpo)!!

        assertEquals(184, config.versao)
        assertEquals(MargensOverscan(2.5f, 1.5f, 3f, 0.5f), config.margens)
        assertEquals(90, config.rotacaoTela)
        assertEquals("4821", config.pinPainel)
        assertEquals(2, config.versaoMinimaBuild)
        assertEquals(RegimeOperacao.CUSTOM, config.horario?.regime)
        assertEquals(1, config.horario?.porDiaDaSemana?.get(DayOfWeek.MONDAY)?.size)
        assertEquals(false, config.politicaUpdate.baixarAutomaticamente)
        assertEquals(12, config.politicaUpdate.horasEntreTentativas)
        assertEquals(2048, config.cache.tetoMegabytes)
    }

    @Test
    fun `sem configVersion e invalida`() {
        assertNull(ConfigRemotaJson.parse("""{"margens": {"superior": 1}}"""))
    }

    @Test
    fun `corpo malformado devolve nulo em vez de lancar`() {
        assertNull(ConfigRemotaJson.parse("não é json"))
        assertNull(ConfigRemotaJson.parse(""))
    }

    @Test
    fun `campo ausente significa nao mexa, nunca zere`() {
        val config = ConfigRemotaJson.parse("""{"configVersion": 7}""")!!

        assertEquals(7, config.versao)
        assertNull(config.margens)
        assertNull(config.rotacaoTela)
        assertNull(config.horario)
        assertNull(config.pinPainel)
    }

    @Test
    fun `rotacao fora do conjunto valido e ignorada`() {
        val config = ConfigRemotaJson.parse("""{"configVersion": 1, "rotacaoTela": 45}""")!!

        assertNull(config.rotacaoTela)
    }

    @Test
    fun `pin fora do formato e ignorado, nunca tranca o painel`() {
        val config = ConfigRemotaJson.parse("""{"configVersion": 1, "pinPainel": "12345"}""")!!

        assertNull(config.pinPainel)
    }

    @Test
    fun `faixa com horario invalido e descartada sem derrubar o resto`() {
        val corpo = """
            {
              "configVersion": 3,
              "operacao": {
                "regime": "CUSTOM",
                "porDiaDaSemana": {"ter": [{"inicio": "25:00", "fim": "18:00"}, {"inicio": "09:00", "fim": "12:00"}]}
              }
            }
        """.trimIndent()

        val config = ConfigRemotaJson.parse(corpo)!!

        assertEquals(1, config.horario?.porDiaDaSemana?.get(DayOfWeek.TUESDAY)?.size)
    }

    @Test
    fun `horasEntreTentativas e limitada a um intervalo razoavel`() {
        val config = ConfigRemotaJson.parse(
            """{"configVersion": 1, "update": {"horasEntreTentativas": 9999}}"""
        )!!

        assertTrue(config.politicaUpdate.horasEntreTentativas <= 72)
    }
}
