package br.com.mostrai.player.cache

import androidx.test.core.app.ApplicationProvider
import br.com.mostrai.player.playlist.ItemPlaylist
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class CacheMidiaTest {

    private val itemEsquemaInesperado = ItemPlaylist(
        itemProgramacaoId = "slot-1",
        criativoId = "crv-1",
        duracaoSegundos = 10,
        url = "ftp://exemplo.invalido/video.mp4",
        anuncianteId = "anunciante",
        autoanuncio = false,
        institucional = false,
        contabiliza = true,
    )

    @Test
    fun `url com esquema que nao e http cai para null, nunca lanca excecao`() {
        // openConnection() pra "ftp://" devolve uma conexão de outro tipo, e
        // o cast pra HttpURLConnection falharia com ClassCastException, não
        // IOException — sem a guarda em CacheMidia.baixarPara, isso
        // escaparia do catch(IOException) de resolver() e derrubaria o app.
        // resolver() promete cair pra tocar direto da URL nesse caso, nunca
        // travar a exibição.
        val cache = CacheMidia(ApplicationProvider.getApplicationContext())
        assertNull(cache.resolver(itemEsquemaInesperado))
    }
}
