package br.com.mostrai.player.atualizacao

import android.net.Uri
import br.com.mostrai.player.atualizacao.AcessoUsb.Acesso
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Como o Player lê o pendrive em cada Android — nunca com "todos os arquivos". */
@RunWith(RobolectricTestRunner::class)
class AcessoUsbTest {

    private val volume = VolumeUsb("ABCD-1234", File("/storage/ABCD-1234"))
    private val arvore = Uri.parse("content://com.android.externalstorage.documents/tree/ABCD-1234%3A")

    private fun decidir(
        sdk: Int,
        permissao: Boolean = true,
        legivel: Boolean = true,
        arvore: Uri? = null,
        seletor: Boolean = true,
        volume: VolumeUsb = this.volume,
    ) = AcessoUsb.decidir(volume, sdk, permissao, { legivel }, arvore, seletor)

    @Test
    fun `TCL Android 8 com permissao le o pendrive direto`() {
        val a = decidir(sdk = 26)
        assertTrue(a is Acesso.Direto)
        assertEquals(volume.raiz, (a as Acesso.Direto).raiz)
        assertTrue(decidir(sdk = 29) is Acesso.Direto)
    }

    @Test
    fun `Android 8 sem a permissao de leitura pede a permissao`() {
        assertSame(Acesso.PrecisaPermissaoLeitura, decidir(sdk = 26, permissao = false))
    }

    @Test
    fun `firmware que esconde o USB cai no seletor do Android`() {
        assertSame(Acesso.PrecisaSeletor, decidir(sdk = 26, legivel = false))
        assertSame(Acesso.PrecisaSeletor, decidir(sdk = 26, volume = volume.copy(raiz = null)))
        assertSame(Acesso.Indisponivel, decidir(sdk = 26, legivel = false, seletor = false))
    }

    @Test
    fun `Android 11 em diante nunca le direto - so pela pasta autorizada`() {
        assertFalse(AcessoUsb.leituraDiretaPossivel(30))
        assertSame(Acesso.PrecisaSeletor, decidir(sdk = 30))
        assertSame(Acesso.PrecisaSeletor, decidir(sdk = 36, permissao = false))
        assertSame(Acesso.Indisponivel, decidir(sdk = 34, seletor = false))
    }

    @Test
    fun `pasta autorizada antes vale sem pedir de novo`() {
        val a = decidir(sdk = 34, arvore = arvore)
        assertTrue(a is Acesso.Documento)
        assertEquals(arvore, (a as Acesso.Documento).arvore)
        assertTrue("Android 8 sem permissão, mas com pasta autorizada", decidir(sdk = 26, permissao = false, arvore = arvore) is Acesso.Documento)
    }
}
