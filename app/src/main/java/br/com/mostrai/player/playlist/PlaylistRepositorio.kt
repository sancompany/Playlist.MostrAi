package br.com.mostrai.player.playlist

import android.content.Context
import br.com.mostrai.player.network.MostraiApi
import br.com.mostrai.player.network.PlaylistJson
import br.com.mostrai.player.network.ResultadoHttp

/**
 * Fonte da playlist atual: o servidor; sem ele, a última válida (contrato
 * §7: "internet caiu ≠ tela parou"); sem nenhuma das duas, nada.
 *
 * Chamar [buscar] de uma thread de fundo — faz rede e E/S de disco.
 */
class PlaylistRepositorio(
    context: Context,
    private val api: MostraiApi,
) {
    private val cache = PlaylistCache(context)

    enum class Origem {
        SERVIDOR,
        CACHE,

        /** Nem servidor nem cache. */
        NENHUMA,

        /** 403: tela em reparo/inativa — não exibe anúncios (contrato §4). */
        SUSPENSA,

        /** 401: a credencial acabou de ser recusada. */
        RECUSADA,
    }

    data class Resultado(
        val playlist: Playlist,
        val relogio: RelogioJanela?,
        val origem: Origem,
        /** Por que não veio do servidor, para o diário. Null quando veio. */
        val falha: String?,
    )

    fun buscar(): Resultado = when (val resposta = api.buscarPlaylist()) {
        is ResultadoHttp.Ok -> {
            val (playlist, corpo) = resposta.valor
            val relogio = playlist.servidorAgora?.let { RelogioJanela.agora(it) }
            cache.salvar(corpo, relogio)
            Resultado(playlist, relogio, Origem.SERVIDOR, null)
        }
        is ResultadoHttp.TelaSuspensa -> {
            // Sem apagar, uma queda de rede durante o reparo voltaria a tocar
            // os anúncios da última playlist guardada.
            cache.limpar()
            Resultado(Playlist.VAZIA, null, Origem.SUSPENSA, "tela suspensa no cadastro (HTTP 403)")
        }
        is ResultadoHttp.CredencialRecusada ->
            Resultado(Playlist.VAZIA, null, Origem.RECUSADA, "credencial recusada (HTTP 401)")
        else -> ultimaValida(resposta.codigoDiagnostico())
    }

    private fun ultimaValida(motivo: String): Resultado {
        val salva = cache.carregar()
        val playlist = salva?.let { PlaylistJson.parse(it.corpoBruto) }
            ?: return Resultado(Playlist.VAZIA, null, Origem.NENHUMA, motivo)
        // Âncora só serve se o relógio monotônico não deu a volta desde que
        // ela foi criada — reboot real invalida a retomada por tempo (7.1).
        return Resultado(playlist, salva.ancora?.takeIf { it.valida() }, Origem.CACHE, motivo)
    }
}
