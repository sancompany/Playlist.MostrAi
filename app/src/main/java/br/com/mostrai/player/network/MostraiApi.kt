package br.com.mostrai.player.network

import android.util.Log
import br.com.mostrai.player.config.ConfigAparelho
import br.com.mostrai.player.config.MargensOverscan
import br.com.mostrai.player.playlist.Playlist
import br.com.mostrai.player.proof.EventoExibicao
import java.io.IOException

/**
 * Cliente HTTP da API do Mostraí.
 *
 * A chave do aparelho vai sempre no header X-Aparelho-Id (nunca usuário e
 * senha — veto formal do projeto). As rotas seguem o padrão atual descrito
 * na seção 5 (`/playlist/:dispositivoId`, `/player/:dispositivoId/played`,
 * `/player/:dispositivoId/heartbeat`); o contrato novo (seção 6) muda o
 * formato do corpo, não o caminho.
 */
// open: FilaProofOfPlayTest cria um dublê que sobrescreve enviarLote/enviarLegado
// para testar a lógica de fila sem rede de verdade — não é extensão de produto.
open class MostraiApi(
    private val config: ConfigAparelho,
    private val http: HttpCliente = HttpCliente(),
) {
    sealed class RespostaPlaylist {
        data class Sucesso(val playlist: Playlist, val corpoBruto: String) : RespostaPlaylist()
        data class ErroAparelho(val codigo: Int) : RespostaPlaylist()
        data class Transitorio(val motivo: String) : RespostaPlaylist()
    }

    fun buscarPlaylist(): RespostaPlaylist {
        val base = config.baseUrl ?: return RespostaPlaylist.Transitorio("sem baseUrl configurada")
        val dispositivoId = config.dispositivoId ?: return RespostaPlaylist.Transitorio("sem dispositivoId")
        return try {
            val resposta = http.get("$base/playlist/$dispositivoId", cabecalhosAuth())
            when (resposta.codigo) {
                200 -> RespostaPlaylist.Sucesso(PlaylistJson.parse(resposta.corpo), resposta.corpo)
                401, 403, 404 -> RespostaPlaylist.ErroAparelho(resposta.codigo)
                else -> RespostaPlaylist.Transitorio("HTTP ${resposta.codigo}")
            }
        } catch (e: IOException) {
            RespostaPlaylist.Transitorio(e.message ?: "falha de rede")
        } catch (e: Exception) {
            Log.w(TAG, "resposta de /playlist não reconhecida", e)
            RespostaPlaylist.Transitorio("resposta não reconhecida")
        }
    }

    sealed class RespostaPlayed {
        data class Sucesso(val resultadosPorId: Map<String, String>) : RespostaPlayed()
        data class SucessoLegado(val contou: Boolean) : RespostaPlayed()
        data class ErroPayload(val codigo: Int) : RespostaPlayed()
        data class ErroAparelho(val codigo: Int) : RespostaPlayed()
        data class RespeitarEspera(val segundos: Int) : RespostaPlayed()
        data class Transitorio(val motivo: String) : RespostaPlayed()
    }

    open fun enviarLote(eventos: List<EventoExibicao>): RespostaPlayed {
        val base = config.baseUrl ?: return RespostaPlayed.Transitorio("sem baseUrl configurada")
        val dispositivoId = config.dispositivoId ?: return RespostaPlayed.Transitorio("sem dispositivoId")
        return try {
            val resposta = http.post(
                "$base/player/$dispositivoId/played", cabecalhosAuth(), PlayedJson.corpoLote(eventos),
            )
            when (resposta.codigo) {
                200 -> RespostaPlayed.Sucesso(PlayedJson.parseResultados(resposta.corpo))
                400 -> RespostaPlayed.ErroPayload(400)
                401, 403, 404 -> RespostaPlayed.ErroAparelho(resposta.codigo)
                429 -> RespostaPlayed.RespeitarEspera(retryAfter(resposta.cabecalhos))
                else -> RespostaPlayed.Transitorio("HTTP ${resposta.codigo}")
            }
        } catch (e: IOException) {
            RespostaPlayed.Transitorio(e.message ?: "falha de rede")
        } catch (e: Exception) {
            // Mesma guarda de buscarPlaylist: PlayedJson.parseResultados roda
            // dentro deste try, e um 200 com corpo malformado lança
            // JSONException, não IOException — sem isto, um envio de
            // proof-of-play derrubaria o app por causa de uma resposta ruim.
            Log.w(TAG, "resposta de /played (lote) não reconhecida", e)
            RespostaPlayed.Transitorio("resposta não reconhecida")
        }
    }

    open fun enviarLegado(anuncianteId: String): RespostaPlayed {
        val base = config.baseUrl ?: return RespostaPlayed.Transitorio("sem baseUrl configurada")
        val dispositivoId = config.dispositivoId ?: return RespostaPlayed.Transitorio("sem dispositivoId")
        return try {
            val resposta = http.post(
                "$base/player/$dispositivoId/played", cabecalhosAuth(), PlayedJson.corpoLegado(anuncianteId),
            )
            when (resposta.codigo) {
                200 -> RespostaPlayed.SucessoLegado(PlayedJson.parseContouLegado(resposta.corpo))
                400 -> RespostaPlayed.ErroPayload(400)
                401, 403, 404 -> RespostaPlayed.ErroAparelho(resposta.codigo)
                429 -> RespostaPlayed.RespeitarEspera(retryAfter(resposta.cabecalhos))
                else -> RespostaPlayed.Transitorio("HTTP ${resposta.codigo}")
            }
        } catch (e: IOException) {
            RespostaPlayed.Transitorio(e.message ?: "falha de rede")
        } catch (e: Exception) {
            // PlayedJson.parseContouLegado já se protege sozinha (runCatching),
            // mas o resto da função (montar a URL, ler os cabeçalhos) ainda
            // pode lançar algo que não é IOException — mesma guarda das
            // outras duas chamadas desta classe.
            Log.w(TAG, "resposta de /played (legado) não reconhecida", e)
            RespostaPlayed.Transitorio("resposta não reconhecida")
        }
    }

    /**
     * Devolve as margens da safe area que o backend mandou (migration 069 de
     * `sancompany/mostrai`), ou `null` — tanto pra heartbeat que falhou
     * quanto pra resposta sem `margens` (contrato antigo do servidor, ou tela
     * sem dono ainda). `null` nunca zera a margem local: quem chama só
     * atualiza [ConfigAparelho] quando há valor de verdade pra aplicar.
     */
    open fun heartbeat(): MargensOverscan? {
        val base = config.baseUrl ?: return null
        val dispositivoId = config.dispositivoId ?: return null
        return try {
            val resposta = http.post("$base/player/$dispositivoId/heartbeat", cabecalhosAuth(), "")
            if (resposta.codigo !in 200..299) return null
            HeartbeatJson.parseMargens(resposta.corpo)
        } catch (e: IOException) {
            null
        } catch (e: Exception) {
            Log.w(TAG, "resposta de /heartbeat não reconhecida", e)
            null
        }
    }

    private fun retryAfter(cabecalhos: Map<String, List<String>>): Int =
        cabecalhos.entries.firstOrNull { it.key?.equals("Retry-After", ignoreCase = true) == true }
            ?.value?.firstOrNull()?.toIntOrNull() ?: 60

    private fun cabecalhosAuth(): Map<String, String> =
        mapOf("X-Aparelho-Id" to (config.chaveAparelho ?: ""))

    private companion object {
        const val TAG = "MostraiApi"
    }
}
