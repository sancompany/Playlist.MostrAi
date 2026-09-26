package br.com.mostrai.player.network

import android.util.Log
import br.com.mostrai.player.BuildConfig
import br.com.mostrai.player.HostDaApi
import br.com.mostrai.player.config.ConfigAparelho
import br.com.mostrai.player.config.ConfigRemota
import br.com.mostrai.player.config.ConfigRemotaJson
import br.com.mostrai.player.playlist.Playlist
import br.com.mostrai.player.proof.EventoExibicao
import java.io.IOException
import org.json.JSONObject

/**
 * Cliente das 5 rotas do contrato MVP (`sancompany/MostrAi`
 * `docs/player-mvp-contract.md`). Nenhuma outra.
 *
 * O servidor é sempre [br.com.mostrai.player.Produto.BASE_URL]; só os testes
 * trocam [base] (variante debug, ver `HostDaApi`).
 *
 * Toda requisição autenticada leva `X-Aparelho-Key` e `X-Player-Version`.
 * Um 401 em qualquer uma delas chama [aoRecusarCredencial] com a chave que o
 * levou — quem ouve decide apagar a credencial (contrato §4).
 */
// open: os testes de fila e de sincronização substituem as chamadas por
// dublês, sem rede de verdade — não é ponto de extensão do produto.
open class MostraiApi(
    private val config: ConfigAparelho,
    private val http: HttpCliente = HttpCliente(),
    private val base: () -> String = { HostDaApi.base },
) {
    @Volatile
    var aoRecusarCredencial: ((chaveRecusada: String) -> Unit)? = null

    // ----------------------------------------------------------- provisionar

    data class Credenciais(val dispositivoId: String, val chaveAparelho: String)

    sealed class Provisionamento {
        data class Ok(val credenciais: Credenciais) : Provisionamento()

        /** 400 — ID ou código em formato que o servidor recusa. */
        object Invalido : Provisionamento()

        /** 401 — tela inexistente, código errado, expirado ou usado. Não se tenta de novo sozinho. */
        object Recusado : Provisionamento()

        /** 429 — esperar `Retry-After`. */
        data class Limitado(val segundos: Int) : Provisionamento()

        /** Rede, 5xx ou resposta ilegível — a repetição curta do contrato cobre. */
        data class Transitorio(val motivo: String) : Provisionamento()
    }

    /**
     * `POST /player/provisionar` (contrato §3). Não leva credencial: é a rota
     * que existe justamente porque ainda não há uma.
     */
    open fun provisionar(codigoTela: String, codigoInstalacao: String): Provisionamento = try {
        val corpo = JSONObject()
            .put("codigoTela", codigoTela)
            .put("codigoInstalacao", codigoInstalacao)
            .toString()
        val resposta = http.post("${base()}/player/provisionar", cabecalhosBase(), corpo)
        when (resposta.codigo) {
            200 -> {
                val json = JSONObject(resposta.corpo)
                val id = json.optString("dispositivoId").trim().ifBlank { null }
                val chave = json.optString("chaveAparelho").trim().ifBlank { null }
                if (id == null || chave == null) {
                    Provisionamento.Transitorio("resposta sem dispositivoId ou chaveAparelho")
                } else {
                    Provisionamento.Ok(Credenciais(id, chave))
                }
            }
            400 -> Provisionamento.Invalido
            401 -> Provisionamento.Recusado
            429 -> Provisionamento.Limitado(retryAfter(resposta.cabecalhos))
            else -> Provisionamento.Transitorio("HTTP ${resposta.codigo}")
        }
    } catch (e: IOException) {
        Provisionamento.Transitorio(e.message ?: "falha de rede")
    } catch (e: Exception) {
        // Nunca loga o corpo: a resposta de sucesso carrega a chave.
        Provisionamento.Transitorio("resposta não reconhecida")
    }

    // ----------------------------------------------------------------- playlist

    /** `GET /playlist/:dispositivoId` (contrato §7). */
    open fun buscarPlaylist(): ResultadoHttp<Pair<Playlist, String>> =
        chamarAutenticado("playlist") { id, cabecalhos -> http.get("${base()}/playlist/$id", cabecalhos) }
            .transformar { corpo -> PlaylistJson.parse(corpo)?.let { ResultadoHttp.Ok(it to corpo) } }

    // ------------------------------------------------------------------- played

    /** `POST /player/:dispositivoId/played` (contrato §8). Devolve `execucaoId → status`. */
    open fun enviarLote(eventos: List<EventoExibicao>): ResultadoHttp<Map<String, String>> =
        chamarAutenticado("played") { id, cabecalhos ->
            http.post("${base()}/player/$id/played", cabecalhos, PlayedJson.corpoLote(eventos))
        }.transformar { corpo -> PlayedJson.parseResultados(corpo)?.let { ResultadoHttp.Ok(it) } }

    // ---------------------------------------------------------------- heartbeat

    /** `POST /player/:dispositivoId/heartbeat` (contrato §5). */
    open fun heartbeat(corpo: HeartbeatJson.Corpo): ResultadoHttp<HeartbeatJson.Resposta> =
        chamarAutenticado("heartbeat") { id, cabecalhos ->
            http.post("${base()}/player/$id/heartbeat", cabecalhos, HeartbeatJson.corpo(corpo))
        }.transformar { corpo -> HeartbeatJson.parseResposta(corpo)?.let { ResultadoHttp.Ok(it) } }

    // ------------------------------------------------------------------- config

    /** `GET /player/:dispositivoId/config` (contrato §6). Devolve a config e o corpo cru. */
    open fun buscarConfig(): ResultadoHttp<Pair<ConfigRemota, String>> =
        chamarAutenticado("config") { id, cabecalhos -> http.get("${base()}/player/$id/config", cabecalhos) }
            .transformar { corpo -> ConfigRemotaJson.parse(corpo)?.let { ResultadoHttp.Ok(it to corpo) } }

    // ------------------------------------------------------------------ interno

    private inline fun chamarAutenticado(
        nome: String,
        chamada: (dispositivoId: String, cabecalhos: Map<String, String>) -> HttpCliente.Resposta,
    ): ResultadoHttp<String> {
        val id = config.dispositivoId ?: return ResultadoHttp.SemCredencial
        val chave = config.chaveAparelho ?: return ResultadoHttp.SemCredencial
        return try {
            val resposta = chamada(id, cabecalhosBase() + ("X-Aparelho-Key" to chave))
            when (resposta.codigo) {
                in 200..299 -> ResultadoHttp.Ok(resposta.corpo)
                401 -> {
                    aoRecusarCredencial?.invoke(chave)
                    ResultadoHttp.CredencialRecusada(401)
                }
                403 -> ResultadoHttp.TelaSuspensa
                429 -> ResultadoHttp.Limitado(retryAfter(resposta.cabecalhos))
                in 500..599 -> ResultadoHttp.ErroServidor(resposta.codigo)
                else -> ResultadoHttp.RespostaInvalida("HTTP ${resposta.codigo}")
            }
        } catch (e: IOException) {
            ResultadoHttp.SemRede(e.message ?: "falha de rede")
        } catch (e: Exception) {
            Log.w(TAG, "falha inesperada em /$nome: ${e.javaClass.simpleName}")
            ResultadoHttp.RespostaInvalida(e.javaClass.simpleName)
        }
    }

    /** 2xx com corpo que não é o do contrato vira [ResultadoHttp.RespostaInvalida] — nunca lança. */
    private inline fun <T> ResultadoHttp<String>.transformar(ler: (String) -> ResultadoHttp<T>?): ResultadoHttp<T> =
        when (this) {
            is ResultadoHttp.Ok -> runCatching { ler(valor) }.getOrNull()
                ?: ResultadoHttp.RespostaInvalida("corpo fora do contrato")
            else -> @Suppress("UNCHECKED_CAST") (this as ResultadoHttp<T>)
        }

    private fun retryAfter(cabecalhos: Map<String?, List<String>>): Int =
        cabecalhos.entries.firstOrNull { it.key?.equals("Retry-After", ignoreCase = true) == true }
            ?.value?.firstOrNull()?.trim()?.toIntOrNull()?.coerceIn(1, 3600) ?: 60

    private fun cabecalhosBase(): Map<String, String> =
        mapOf("X-Player-Version" to VERSAO_PLAYER)

    companion object {
        private const val TAG = "MostraiApi"

        /** `versionName+versionCode`, formato que o backend lê (contrato §4). */
        const val VERSAO_PLAYER = "${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
    }
}
