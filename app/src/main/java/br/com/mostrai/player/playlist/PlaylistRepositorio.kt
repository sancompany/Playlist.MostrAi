package br.com.mostrai.player.playlist

import android.content.Context
import android.util.Log
import br.com.mostrai.player.network.MostraiApi
import br.com.mostrai.player.network.PlaylistJson

/**
 * Fonte de verdade da playlist atual: busca no servidor, cai para a última
 * cache válida se a rede falhar, e cai para a tela institucional se não
 * houver nem uma coisa nem outra.
 *
 * Chamar [buscar] sempre de uma thread de fundo — faz rede e E/S de disco.
 */
class PlaylistRepositorio(
    context: Context,
    private val api: MostraiApi,
) {
    private val cache = PlaylistCache(context)

    data class Resultado(
        val playlist: Playlist,
        val relogio: RelogioJanela?,
        val origem: Origem,
        val erroAparelho: Int?,
        val falhaTransitoria: String?,
    )

    enum class Origem { SERVIDOR, CACHE, INSTITUCIONAL }

    fun buscar(): Resultado {
        return when (val resposta = api.buscarPlaylist()) {
            is MostraiApi.RespostaPlaylist.Sucesso -> {
                val relogio = resposta.playlist.servidorAgora?.let { RelogioJanela.agora(it) }
                cache.salvar(resposta.corpoBruto, relogio)
                Resultado(resposta.playlist, relogio, Origem.SERVIDOR, null, null)
            }
            is MostraiApi.RespostaPlaylist.ErroAparelho -> {
                Log.w(TAG, "erro do aparelho (${resposta.codigo}) ao buscar playlist")
                carregarFallback(erroAparelho = resposta.codigo, falhaTransitoria = null)
            }
            is MostraiApi.RespostaPlaylist.Transitorio -> {
                Log.i(TAG, "falha transitória ao buscar playlist: ${resposta.motivo}")
                carregarFallback(erroAparelho = null, falhaTransitoria = resposta.motivo)
            }
        }
    }

    private fun carregarFallback(erroAparelho: Int?, falhaTransitoria: String?): Resultado {
        val salva = cache.carregar()
        if (salva != null) {
            val playlist = runCatching { PlaylistJson.parse(salva.corpoBruto) }.getOrNull()
            // Uma âncora só serve se o relógio monotônico não deu a volta desde
            // que ela foi criada — reboot real invalida a retomada por tempo
            // (decisão fechada com o GPT, item 7.1).
            val relogioValido = salva.ancora?.takeIf { it.valida() }
            if (playlist != null) {
                return Resultado(playlist, relogioValido, Origem.CACHE, erroAparelho, falhaTransitoria)
            }
        }
        return Resultado(Playlist.somenteInstitucional(), null, Origem.INSTITUCIONAL, erroAparelho, falhaTransitoria)
    }

    private companion object {
        const val TAG = "PlaylistRepositorio"
    }
}
