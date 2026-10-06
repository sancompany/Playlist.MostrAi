package br.com.mostrai.player.atualizacao

import br.com.mostrai.player.atualizacao.ApkFalso.OFICIAL
import br.com.mostrai.player.atualizacao.ApkFalso.OUTRA
import br.com.mostrai.player.atualizacao.ValidacaoAtualizacao.Veredito
import br.com.mostrai.player.atualizacao.VerificadorUsb.Resultado
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Do pendrive à cópia privada aprovada: só um APK legítimo fica no
 * aparelho, e nada parcial sobra de uma cópia que falhou.
 */
class VerificadorUsbTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var pendrive: File
    private lateinit var privado: File
    private var livre: () -> Long = { 50L shl 30 }
    private val reserva = 1L shl 30

    private fun verificador() =
        VerificadorUsb(privado, ApkFalso.LeitorFalso, { ApkFalso.instalado }, OFICIAL, { livre() }, { reserva })

    private fun verificar(origem: OrigemPacote = OrigemArquivo(pendrive)) = verificador().verificar(origem)

    /** Nada da tentativa ficou na área privada (nem cópia parcial, nem final). */
    private fun nadaFicou() = privado.listFiles().orEmpty().isEmpty()

    @Before
    fun preparar() {
        pendrive = tmp.newFolder("pendrive")
        privado = File(tmp.root, "privado/atualizacao")
    }

    @Test
    fun `pendrive comum nao tem atualizacao e nao copia nada`() {
        File(pendrive, "DCIM").mkdirs()
        File(pendrive, "DCIM/foto.jpg").writeBytes(ByteArray(1024))
        File(pendrive, "outro-app.apk").writeBytes(ApkFalso.bytes(pacote = "com.exemplo"))
        assertSame(Resultado.SemAtualizacao, verificar())
        assertTrue(nadaFicou())
    }

    @Test
    fun `nao procura em subpastas fora do caminho oficial`() {
        File(pendrive, "backup/MOSTRAI/update").mkdirs()
        File(pendrive, "backup/MOSTRAI/update/Mostrai-Player.apk").writeBytes(ApkFalso.bytes())
        File(pendrive, "Downloads").mkdirs()
        File(pendrive, "Downloads/Mostrai-Player.apk").writeBytes(ApkFalso.bytes())
        assertSame(Resultado.SemAtualizacao, verificar())
    }

    @Test
    fun `candidato valido no caminho oficial e copiado e aprovado`() {
        val apk = ApkFalso.bytes()
        ApkFalso.gravarOficial(pendrive, apk)

        val r = verificar()

        assertTrue("$r", r is Resultado.Candidato)
        r as Resultado.Candidato
        assertEquals(7L, r.apk.versionCode)
        assertEquals(sha256Hex(apk), r.sha256)
        assertEquals(File(privado, "Mostrai-Player.apk"), r.arquivo)
        assertArrayEquals(apk, r.arquivo.readBytes())
        assertFalse("a cópia temporária sumiu", File(privado, VerificadorUsb.NOME_TEMPORARIO).exists())
    }

    @Test
    fun `APK na raiz do pendrive vale como reserva`() {
        File(pendrive, "Mostrai-Player.apk").writeBytes(ApkFalso.bytes(versionCode = 9, versionName = "3.0.3"))
        val r = verificar()
        assertTrue(r is Resultado.Candidato)
        assertEquals(9L, (r as Resultado.Candidato).apk.versionCode)
    }

    @Test
    fun `o caminho oficial tem prioridade sobre a raiz`() {
        ApkFalso.gravarOficial(pendrive, ApkFalso.bytes(versionCode = 7))
        File(pendrive, "Mostrai-Player.apk").writeBytes(ApkFalso.bytes(versionCode = 9))
        assertEquals(7L, (verificar() as Resultado.Candidato).apk.versionCode)
    }

    @Test
    fun `pacote errado e recusado e nada fica no aparelho`() {
        ApkFalso.gravarOficial(pendrive, ApkFalso.bytes(pacote = "com.exemplo.outro"))
        val r = verificar()
        assertSame(Veredito.PacoteErrado, (r as Resultado.Recusado).veredito)
        assertTrue(nadaFicou())
    }

    @Test
    fun `assinatura errada e recusada`() {
        ApkFalso.gravarOficial(pendrive, ApkFalso.bytes(certificados = setOf(OUTRA)), json = null)
        assertSame(Veredito.AssinaturaErrada, (verificar() as Resultado.Recusado).veredito)
        assertTrue(nadaFicou())
    }

    @Test
    fun `hash errado no update json e recusado`() {
        val apk = ApkFalso.bytes()
        ApkFalso.gravarOficial(pendrive, apk, ApkFalso.json(apk, sha256 = "0".repeat(64)))
        assertSame(Veredito.HashErrado, (verificar() as Resultado.Recusado).veredito)
        assertTrue(nadaFicou())
    }

    @Test
    fun `APK trocado depois do empacotamento e pego pelo hash`() {
        val original = ApkFalso.bytes()
        val trocado = ApkFalso.bytes(versionName = "3.0.2-alterado")
        ApkFalso.gravarOficial(pendrive, trocado, ApkFalso.json(original))
        assertSame(Veredito.HashErrado, (verificar() as Resultado.Recusado).veredito)
    }

    @Test
    fun `versao igual a instalada nao e oferecida`() {
        ApkFalso.gravarOficial(pendrive, ApkFalso.bytes(versionCode = 6, versionName = "3.0.1"), json = null)
        assertSame(Veredito.MesmaVersao, (verificar() as Resultado.Recusado).veredito)
        assertTrue(nadaFicou())
    }

    @Test
    fun `downgrade e recusado`() {
        ApkFalso.gravarOficial(pendrive, ApkFalso.bytes(versionCode = 4, versionName = "3.0.0"), json = null)
        assertSame(Veredito.Anterior, (verificar() as Resultado.Recusado).veredito)
        assertTrue(nadaFicou())
    }

    @Test
    fun `upgrade e aceito`() {
        ApkFalso.gravarOficial(pendrive, ApkFalso.bytes(versionCode = 8, versionName = "3.1.0"), json = null)
        val r = verificar() as Resultado.Candidato
        assertEquals("3.1.0", r.apk.versionName)
        assertEquals("Atualização 3.1.0 pronta para instalar.", r.mensagem)
    }

    @Test
    fun `arquivo que nao e APK e recusado como corrompido`() {
        ApkFalso.gravarOficial(pendrive, "PK\u0003\u0004 lixo".toByteArray(), json = null)
        assertSame(Veredito.Corrompido, (verificar() as Resultado.Recusado).veredito)
        assertTrue(nadaFicou())
    }

    @Test
    fun `update json ilegivel recusa`() {
        ApkFalso.gravarOficial(pendrive, ApkFalso.bytes(), json = "não é json")
        assertSame(Veredito.JsonIlegivel, (verificar() as Resultado.Recusado).veredito)
    }

    @Test
    fun `pendrive removido durante a copia falha com seguranca`() {
        val apk = ApkFalso.bytes() + ByteArray(200_000)
        val origem = origem(apk.size.toLong()) {
            // Erro de E/S no meio da leitura, com o caminho na mensagem.
            object : InputStream() {
                var lidos = 0
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (lidos > 100_000) throw IOException("/storage/ABCD-1234/MOSTRAI/update/Mostrai-Player.apk: EIO")
                    val n = minOf(len, 50_000)
                    lidos += n
                    return n
                }
            }
        }

        val r = verificar(origem)

        assertTrue(r is Resultado.FalhaLeitura)
        assertFalse("nada de caminho na tela", r.mensagem.contains("/storage"))
        assertTrue(nadaFicou())
    }

    @Test
    fun `pendrive removido que so encerra a leitura antes do fim tambem falha`() {
        val apk = ApkFalso.bytes()
        // O tamanho anunciado é maior que o que chega: a cópia ficou pela metade.
        val origem = origem(apk.size + 10_000L) { ByteArrayInputStream(apk) }
        val r = verificar(origem)
        assertTrue(r is Resultado.FalhaLeitura)
        assertTrue(r.mensagem.contains("cópia incompleta"))
        assertTrue(nadaFicou())
    }

    @Test
    fun `sem espaco para copiar nao copia nem apaga nada`() {
        val apk = ApkFalso.bytes()
        ApkFalso.gravarOficial(pendrive, apk)
        // Copiar levaria o disco abaixo da reserva do cache.
        livre = { reserva + apk.size - 1 }
        assertSame(Resultado.SemEspaco, verificar())
        assertTrue(nadaFicou())
    }

    @Test
    fun `espaco que acaba durante a copia interrompe e apaga o parcial`() {
        val tamanho = VerificadorUsb.CONFERENCIA_DE_ESPACO_BYTES + 1_000_000
        var chamadas = 0
        // Antes de copiar havia folga; outro processo enche o disco no meio.
        livre = { if (chamadas++ == 0) 50L shl 30 else 0L }
        val origem = origem(tamanho) { ByteArrayInputStream(ByteArray(tamanho.toInt())) }

        assertSame(Resultado.SemEspaco, verificar(origem))
        assertTrue(chamadas >= 2)
        assertTrue(nadaFicou())
    }

    @Test
    fun `APK grande demais e recusado sem ler o pendrive`() {
        val origem = origem(VerificadorUsb.TAMANHO_MAXIMO + 1) { error("não deveria abrir") }
        assertSame(Resultado.GrandeDemais, verificar(origem))
    }

    @Test
    fun `pendrive que some ao localizar nao derruba nada`() {
        val origem = object : OrigemPacote {
            override fun localizar(): OrigemPacote.Localizado? = throw SecurityException("sem acesso a /storage/ABCD")
        }
        val r = verificar(origem)
        assertTrue(r is Resultado.FalhaLeitura)
        assertFalse(r.mensagem.contains("/storage"))
    }

    @Test
    fun `nova verificacao reaproveita o lugar da copia aprovada e limpar apaga`() {
        ApkFalso.gravarOficial(pendrive, ApkFalso.bytes(versionCode = 7))
        val v = verificador()
        v.verificar(OrigemArquivo(pendrive)) as Resultado.Candidato
        val nova = ApkFalso.bytes(versionCode = 8)
        ApkFalso.gravarOficial(pendrive, nova, ApkFalso.json(nova, versionCode = 8))
        val segunda = v.verificar(OrigemArquivo(pendrive)) as Resultado.Candidato
        assertEquals(8L, ApkFalso.LeitorFalso.ler(segunda.arquivo)!!.versionCode)
        assertEquals(1, privado.listFiles()!!.size)

        v.limpar()
        assertTrue(nadaFicou())
    }

    private fun origem(tamanho: Long, abrir: () -> InputStream) = object : OrigemPacote {
        override fun localizar() = OrigemPacote.Localizado(tamanho, abrir) { null }
    }
}
