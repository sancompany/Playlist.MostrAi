package br.com.mostrai.player.network

import br.com.mostrai.player.config.ConfigAparelho
import br.com.mostrai.player.estado.DiarioBordo

/**
 * Um ciclo de heartbeat e a config que ele pode puxar (contrato §5 e §6).
 * Fora da Activity para caber em teste com um dublê de [MostraiApi]; nunca
 * toca em View — devolve [Efeitos] para a Activity aplicar.
 */
class Sincronizacao(
    private val config: ConfigAparelho,
    private val api: MostraiApi,
    private val diario: DiarioBordo,
) {

    data class Efeitos(
        /** O servidor sinalizou playlist nova (`playlist.atualizar`). */
        val atualizarPlaylist: Boolean = false,
        /** Uma config nova foi aplicada: margens e horário mudaram. */
        val configAplicada: Boolean = false,
        /** Heartbeat chegou ao servidor. Falso com a TV sem rede. */
        val alcancouServidor: Boolean = false,
    )

    fun heartbeat(corpo: HeartbeatJson.Corpo): Efeitos =
        when (val resultado = api.heartbeat(corpo)) {
            is ResultadoHttp.Ok -> Efeitos(
                atualizarPlaylist = resultado.valor.atualizarPlaylist,
                configAplicada = sincronizarConfigSeNecessario(resultado.valor.configVersion),
                alcancouServidor = true,
            )
            // 401 já foi entregue a MostraiApi.aoRecusarCredencial. Rede
            // caída, 5xx e 429 não são falha do aparelho: não sujam o
            // diário, que é o que o admin mostra como "Erro do Player".
            else -> Efeitos()
        }

    /**
     * `GET /config` quando a versão do servidor difere da aplicada.
     *
     * Serializada (BUG-010): duas buscas simultâneas podiam aplicar a mais
     * velha por último. A versão só é gravada junto com o corpo, depois de
     * lido por inteiro — falhar aqui deixa a config anterior intacta, e o
     * próximo heartbeat tenta de novo.
     */
    @Synchronized
    fun sincronizarConfigSeNecessario(versaoServidor: Int?): Boolean {
        val versao = versaoServidor ?: return false
        if (versao == config.configVersionAplicada) return false

        return when (val resultado = api.buscarConfig()) {
            is ResultadoHttp.Ok -> {
                val (remota, corpo) = resultado.valor
                if (!config.aplicarConfig(remota, corpo)) {
                    diario.registrar(DiarioBordo.Codigo.CONFIG_FALHOU, "não foi possível gravar a config")
                    return false
                }
                diario.registrar(DiarioBordo.Codigo.CONFIG_APLICADA, "versão ${remota.versao}")
                true
            }
            is ResultadoHttp.CredencialRecusada, is ResultadoHttp.SemRede -> false
            else -> {
                diario.registrar(
                    DiarioBordo.Codigo.CONFIG_FALHOU,
                    "não foi possível buscar a config: ${resultado.codigoDiagnostico()}",
                )
                false
            }
        }
    }
}
