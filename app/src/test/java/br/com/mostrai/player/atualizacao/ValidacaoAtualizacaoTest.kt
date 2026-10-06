package br.com.mostrai.player.atualizacao

import br.com.mostrai.player.atualizacao.ApkFalso.OFICIAL
import br.com.mostrai.player.atualizacao.ApkFalso.OUTRA
import br.com.mostrai.player.atualizacao.ApkFalso.PACOTE
import br.com.mostrai.player.atualizacao.ValidacaoAtualizacao.Veredito
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** O que faz um APK de pendrive ser oferecido — ou recusado — ao técnico. */
class ValidacaoAtualizacaoTest {

    private val instalado = ApkFalso.instalado
    private val sha = "c".repeat(64)

    private fun apk(pacote: String = PACOTE, code: Long = 7, certs: Set<String> = setOf(OFICIAL)) =
        InfoApk(pacote, code, "3.0.2", certs)

    private fun validar(
        candidato: InfoApk? = apk(),
        json: PacoteUsbJson? = null,
        jsonIlegivel: Boolean = false,
        instalado: InfoApk = this.instalado,
        oficial: String = OFICIAL,
    ) = ValidacaoAtualizacao.validar(candidato, instalado, oficial, sha, json, jsonIlegivel)

    @Test
    fun `upgrade com a chave oficial e o mesmo pacote e oferecido`() {
        val v = validar()
        assertTrue(v is Veredito.Disponivel)
        assertEquals(7L, (v as Veredito.Disponivel).apk.versionCode)
    }

    @Test
    fun `upgrade com update json coerente e oferecido`() {
        assertTrue(validar(json = PacoteUsbJson("3.0.2", 7, sha, OFICIAL)) is Veredito.Disponivel)
        // Campos ausentes no JSON não recusam nada: o APK é que vale.
        assertTrue(validar(json = PacoteUsbJson(null, null, null, null)) is Veredito.Disponivel)
    }

    @Test
    fun `mesma versao nao e oferecida`() {
        assertSame(Veredito.MesmaVersao, validar(candidato = apk(code = 6)))
        assertEquals("Esta versão já está instalada.", Veredito.MesmaVersao.mensagem)
    }

    @Test
    fun `downgrade e recusado`() {
        assertSame(Veredito.Anterior, validar(candidato = apk(code = 4)))
        assertEquals("Versão do USB anterior à instalada.", Veredito.Anterior.mensagem)
    }

    @Test
    fun `outro pacote e ignorado`() {
        assertSame(Veredito.PacoteErrado, validar(candidato = apk(pacote = "com.exemplo.jogo")))
        // Nem um pacote "parecido" passa.
        assertSame(Veredito.PacoteErrado, validar(candidato = apk(pacote = "br.com.mostrai.player.debug")))
    }

    @Test
    fun `outra assinatura e recusada`() {
        assertSame(Veredito.AssinaturaErrada, validar(candidato = apk(certs = setOf(OUTRA))))
        assertSame("sem assinatura", Veredito.AssinaturaErrada, validar(candidato = apk(certs = emptySet())))
        // Dois assinantes, um deles o oficial: não é a mesma identidade.
        assertSame(Veredito.AssinaturaErrada, validar(candidato = apk(certs = setOf(OFICIAL, OUTRA))))
    }

    @Test
    fun `Player instalado fora da chave oficial desliga a atualizacao por USB`() {
        // Ex.: um build debug na bancada — nem o APK oficial é oferecido,
        // porque o Android recusaria a troca de assinatura.
        val debug = instalado.copy(certificados = setOf(OUTRA))
        assertSame(Veredito.PlayerForaDaChaveOficial, validar(instalado = debug))
        assertSame(Veredito.PlayerForaDaChaveOficial, validar(instalado = debug, candidato = apk(certs = setOf(OUTRA))))
    }

    @Test
    fun `hash do update json diferente do APK copiado recusa`() {
        assertSame(Veredito.HashErrado, validar(json = PacoteUsbJson("3.0.2", 7, "d".repeat(64), OFICIAL)))
    }

    @Test
    fun `update json ilegivel recusa o pacote`() {
        assertSame(Veredito.JsonIlegivel, validar(json = null, jsonIlegivel = true))
    }

    @Test
    fun `update json que nao confere com o APK recusa`() {
        assertSame(Veredito.JsonIncoerente, validar(json = PacoteUsbJson("3.0.2", 8, sha, OFICIAL)))
        assertSame(Veredito.JsonIncoerente, validar(json = PacoteUsbJson("3.0.2", 7, sha, OUTRA)))
    }

    @Test
    fun `arquivo que o Android nao le como APK e corrompido`() {
        assertSame(Veredito.Corrompido, validar(candidato = null))
    }

    @Test
    fun `a impressao oficial e comparada sem diferenca de caixa ou espaco`() {
        assertTrue(validar(oficial = " ${OFICIAL.uppercase()}\n") is Veredito.Disponivel)
    }
}
