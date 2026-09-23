package br.com.mostrai.player.network

import android.util.Log
import br.com.mostrai.player.BuildConfig
import br.com.mostrai.player.config.ConfigAparelho
import br.com.mostrai.player.config.ConfigRemota
import br.com.mostrai.player.config.ConfigRemotaJson
import br.com.mostrai.player.playlist.Playlist
import br.com.mostrai.player.proof.EventoExibicao
import java.io.IOException
import org.json.JSONObject

/**
 * Cliente HTTP da API do Mostraí.
 *
 * A chave do aparelho vai no header de autenticação (nunca usuário e senha —
 * veto formal do projeto). Durante a migração V1→V2 ela viaja nos **dois**
 * nomes de header: `X-Aparelho-Id`, que o backend atual conhece, e
 * `X-Aparelho-Key`, que descreve honestamente o que o valor é. O backend
 * novo pode passar a ler só o segundo quando quiser; o player não precisa de
 * release nenhum para isso.
 *
 * Toda requisição autenticada também leva `X-Player-Version` e
 * `X-Player-Contract` — assim o servidor sabe com quem está falando mesmo
 * numa tela cujo heartbeat está falhando, que é justamente quando o
 * diagnóstico importa.
 */
// open: FilaProofOfPlayTest cria um dublê que sobrescreve enviarLote/enviarLegado
// para testar a lógica de fila sem rede de verdade — não é extensão de produto.
open class MostraiApi(
    private val config: ConfigAparelho,
    private val http: HttpCliente = HttpCliente(),
) {
    // ------------------------------------------------------------------ playlist

    sealed class RespostaPlaylist {
        data class Sucesso(val playlist: Playlist, val corpoBruto: String) : RespostaPlaylist()
        data class ErroAparelho(val codigo: Int) : RespostaPlaylist()
        data class Transitorio(val motivo: String) : RespostaPlaylist()
    }

    fun buscarPlaylist(): RespostaPlaylist {
        val base = config.baseUrl ?: return RespostaPlaylist.Transitorio("sem baseUrl configurada")
        val dispositivoId = config.dispositivoId ?: return RespostaPlaylist.Transitorio("sem dispositivoId")
        if (!config.temCredencial) return RespostaPlaylist.ErroAparelho(SEM_CREDENCIAL)
        return try {
            val chave = chaveParaEnviar()
            val resposta = http.get("$base/playlist/$dispositivoId", cabecalhos(chave))
            when (resposta.codigo) {
                200 -> {
                    promoverChaveCandidata(chave)
                    RespostaPlaylist.Sucesso(PlaylistJson.parse(resposta.corpo), resposta.corpo)
                }
                401, 403 -> {
                    descartarChaveCandidata(chave)
                    RespostaPlaylist.ErroAparelho(resposta.codigo)
                }
                404 -> RespostaPlaylist.ErroAparelho(404)
                else -> RespostaPlaylist.Transitorio("HTTP ${resposta.codigo}")
            }
        } catch (e: IOException) {
            RespostaPlaylist.Transitorio(e.message ?: "falha de rede")
        } catch (e: Exception) {
            Log.w(TAG, "resposta de /playlist não reconhecida", e)
            RespostaPlaylist.Transitorio("resposta não reconhecida")
        }
    }

    // ------------------------------------------------------------------- played

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
        if (!config.temCredencial) return RespostaPlayed.ErroAparelho(SEM_CREDENCIAL)
        return try {
            val resposta = http.post(
                "$base/player/$dispositivoId/played", cabecalhos(chaveParaEnviar()), PlayedJson.corpoLote(eventos),
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
        if (!config.temCredencial) return RespostaPlayed.ErroAparelho(SEM_CREDENCIAL)
        return try {
            val resposta = http.post(
                "$base/player/$dispositivoId/played", cabecalhos(chaveParaEnviar()), PlayedJson.corpoLegado(anuncianteId),
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
            Log.w(TAG, "resposta de /played (legado) não reconhecida", e)
            RespostaPlayed.Transitorio("resposta não reconhecida")
        }
    }

    // ---------------------------------------------------------------- heartbeat

    /**
     * Manda o estado do player e lê o que o servidor tem a dizer.
     *
     * Ao contrário da versão anterior, distingue as famílias de falha (R8):
     * chave revogada, servidor fora, rede caída e rota inexistente exigem
     * reações diferentes, e colapsar tudo em `null` apagava essa informação
     * justamente na única chamada periódica que o aparelho faz.
     */
    open fun heartbeat(corpo: HeartbeatJson.Corpo): ResultadoHttp<HeartbeatJson.Resposta> =
        chamarAutenticado("heartbeat") { base, dispositivoId, cabecalhos ->
            http.post("$base/player/$dispositivoId/heartbeat", cabecalhos, HeartbeatJson.corpo(corpo))
        }.mapear { HeartbeatJson.parseResposta(it) }

    // -------------------------------------------------------------------- hello

    open fun hello(dados: HelloJson.Dados): ResultadoHttp<Int?> =
        chamarAutenticado("hello") { base, dispositivoId, cabecalhos ->
            http.post("$base/player/$dispositivoId/hello", cabecalhos, HelloJson.corpo(dados))
        }.mapear { HelloJson.parseConfigVersion(it) }

    // ------------------------------------------------------------------- config

    open fun buscarConfig(): ResultadoHttp<Pair<ConfigRemota, String>> =
        chamarAutenticado("config") { base, dispositivoId, cabecalhos ->
            http.get("$base/player/$dispositivoId/config", cabecalhos)
        }.flatMapear { corpo ->
            val config = ConfigRemotaJson.parse(corpo)
                ?: return@flatMapear ResultadoHttp.RespostaInvalida("config sem configVersion utilizável")
            ResultadoHttp.Ok(config to corpo)
        }

    // ----------------------------------------------------------- provisionamento

    data class Credenciais(val dispositivoId: String, val chaveAparelho: String)

    /**
     * Troca o token de uso único do pendrive por credenciais de verdade.
     *
     * É o que permite o arquivo de provisionamento não carregar o segredo
     * definitivo: um pendrive perdido expõe um token que o servidor já
     * queimou, não a chave permanente de uma tela em operação.
     *
     * Não usa [chamarAutenticado] de propósito — é a única rota que roda
     * justamente porque ainda não há credencial.
     */
    open fun provisionar(token: String): ResultadoHttp<Credenciais> {
        val base = config.baseUrl ?: return ResultadoHttp.SemCredencial("sem baseUrl configurada")
        return try {
            val corpo = JSONObject().put("tokenProvisionamento", token).toString()
            val resposta = http.post("$base/player/provisionar", cabecalhosBase(), corpo)
            when (val classificada = classificar(resposta)) {
                is ResultadoHttp.Ok -> {
                    val json = JSONObject(classificada.valor)
                    val dispositivoId = json.optString("dispositivoId").ifBlank { null }
                    val chave = json.optString("chaveAparelho").ifBlank { null }
                    if (dispositivoId == null || chave == null) {
                        ResultadoHttp.RespostaInvalida("resposta sem dispositivoId ou chaveAparelho")
                    } else {
                        ResultadoHttp.Ok(Credenciais(dispositivoId, chave))
                    }
                }
                else -> classificada as ResultadoHttp<Credenciais>
            }
        } catch (e: IOException) {
            ResultadoHttp.SemRede(e.message ?: "falha de rede")
        } catch (e: Exception) {
            ResultadoHttp.RespostaInvalida(e.message ?: "resposta não reconhecida")
        }
    }

    // ------------------------------------------------------------------ interno

    private inline fun chamarAutenticado(
        nome: String,
        chamada: (base: String, dispositivoId: String, cabecalhos: Map<String, String>) -> HttpCliente.Resposta,
    ): ResultadoHttp<String> {
        val base = config.baseUrl ?: return ResultadoHttp.SemCredencial("sem baseUrl configurada")
        val dispositivoId = config.dispositivoId ?: return ResultadoHttp.SemCredencial("sem dispositivoId")
        // R7: chave em branco não é credencial. Mandar header vazio fingindo
        // autenticação faz o servidor responder 401 e esconde a causa real —
        // que o aparelho simplesmente não foi provisionado.
        if (!config.temCredencial) return ResultadoHttp.SemCredencial("sem chave de aparelho")

        val chave = chaveParaEnviar()
        return try {
            val resultado = classificar(chamada(base, dispositivoId, cabecalhos(chave)))
            when (resultado) {
                is ResultadoHttp.Ok -> promoverChaveCandidata(chave)
                is ResultadoHttp.ErroAutenticacao -> descartarChaveCandidata(chave)
                else -> Unit
            }
            resultado
        } catch (e: IOException) {
            ResultadoHttp.SemRede(e.message ?: "falha de rede")
        } catch (e: Exception) {
            Log.w(TAG, "resposta de /$nome não reconhecida", e)
            ResultadoHttp.RespostaInvalida(e.message ?: "resposta não reconhecida")
        }
    }

    private fun classificar(resposta: HttpCliente.Resposta): ResultadoHttp<String> = when (resposta.codigo) {
        in 200..299 -> ResultadoHttp.Ok(resposta.corpo)
        401, 403 -> ResultadoHttp.ErroAutenticacao(resposta.codigo)
        404 -> ResultadoHttp.NaoEncontrado
        429 -> ResultadoHttp.Limitado(retryAfter(resposta.cabecalhos))
        in 500..599 -> ResultadoHttp.ErroServidor(resposta.codigo)
        else -> ResultadoHttp.RespostaInvalida("HTTP ${resposta.codigo}")
    }

    private inline fun <T> ResultadoHttp<String>.mapear(transformar: (String) -> T): ResultadoHttp<T> =
        when (this) {
            is ResultadoHttp.Ok -> ResultadoHttp.Ok(transformar(valor))
            else -> @Suppress("UNCHECKED_CAST") (this as ResultadoHttp<T>)
        }

    private inline fun <T> ResultadoHttp<String>.flatMapear(
        transformar: (String) -> ResultadoHttp<T>,
    ): ResultadoHttp<T> = when (this) {
        is ResultadoHttp.Ok -> transformar(valor)
        else -> @Suppress("UNCHECKED_CAST") (this as ResultadoHttp<T>)
    }

    /**
     * A chave candidata só vira oficial depois de o servidor aceitar uma
     * requisição feita com ela (Parte 14). Enquanto não aceitar, a antiga
     * continua valendo e a tela continua no ar.
     *
     * Só conta a resposta de uma requisição que **levou** a candidata
     * (BUG-013). Playlist, heartbeat, config e envio de proof-of-play rodam
     * em paralelo; a candidata chega pela resposta de um deles enquanto os
     * outros já saíram com a chave antiga, e o 200 desses outros não prova
     * nada sobre ela. Antes, promovia assim mesmo — e se a candidata não
     * existisse no servidor, a tela jogava fora a única chave válida.
     */
    @Synchronized
    private fun promoverChaveCandidata(chaveEnviada: String?) {
        val candidata = config.chaveCandidata ?: return
        if (candidata != chaveEnviada) return
        config.chaveAparelho = candidata
        config.chaveCandidata = null
        Log.i(TAG, "chave de aparelho rotacionada com sucesso")
    }

    /**
     * A candidata não serve: volta para a antiga sem drama. Mesma regra da
     * promoção — um 401 de requisição feita com a chave antiga fala da
     * antiga, não da candidata.
     */
    @Synchronized
    private fun descartarChaveCandidata(chaveEnviada: String?) {
        if (config.chaveCandidata != null && config.chaveCandidata == chaveEnviada) {
            Log.w(TAG, "chave candidata rejeitada pelo servidor, mantendo a anterior")
            config.chaveCandidata = null
        }
    }

    private fun retryAfter(cabecalhos: Map<String, List<String>>): Int =
        cabecalhos.entries.firstOrNull { it.key?.equals("Retry-After", ignoreCase = true) == true }
            ?.value?.firstOrNull()?.toIntOrNull() ?: 60

    /** Versão e contrato vão em toda chamada, autenticada ou não. */
    private fun cabecalhosBase(): Map<String, String> = mapOf(
        "X-Player-Version" to "${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}",
        "X-Player-Contract" to HeartbeatJson.CONTRATO.toString(),
    )

    /**
     * Durante a rotação, a candidata é quem vai — é assim que ela prova que
     * funciona. Se falhar, promoverChaveCandidata nunca roda e a antiga
     * continua gravada. Lida uma vez por requisição: quem chama guarda o
     * valor para saber, na resposta, qual chave ela está julgando.
     */
    private fun chaveParaEnviar(): String? = config.chaveCandidata ?: config.chaveAparelho

    private fun cabecalhos(chave: String?): Map<String, String> {
        if (chave == null) return cabecalhosBase()
        return cabecalhosBase() + mapOf(
            "X-Aparelho-Id" to chave,
            "X-Aparelho-Key" to chave,
        )
    }

    private companion object {
        const val TAG = "MostraiApi"

        /** Sentinela local: não houve requisição, falta credencial. */
        const val SEM_CREDENCIAL = -1
    }
}
