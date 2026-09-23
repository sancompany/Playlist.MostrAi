package br.com.mostrai.player.network

import android.util.Log
import br.com.mostrai.player.config.ConfigAparelho
import br.com.mostrai.player.config.MargensOverscan
import br.com.mostrai.player.estado.DiarioBordo
import br.com.mostrai.player.update.Atualizador

/**
 * Conversa com o contrato V2 e degrada sozinha quando ele não existe.
 *
 * Todo o miolo de "o backend já entende isso?" mora aqui, fora da Activity, e
 * a regra é sempre a mesma: **404 numa rota V2 não é erro, é um backend V1**.
 * O player marca a indisponibilidade, segue com o comportamento antigo e
 * volta a tentar depois — ninguém precisa configurar nada, e a atualização do
 * servidor pode acontecer a qualquer momento sem release do app.
 *
 * Separado da Activity para poder ser testado com um dublê de [MostraiApi],
 * sem TV e sem rede.
 */
class SincronizacaoV2(
    private val config: ConfigAparelho,
    private val api: MostraiApi,
    private val diario: DiarioBordo,
    private val atualizador: Atualizador,
) {

    /** O que o ciclo pede à Activity depois de falar com o servidor. */
    data class Efeitos(
        val atualizarPlaylist: Boolean = false,
        val margens: MargensOverscan? = null,
        val rotacao: Int? = null,
        val autenticacaoFalhou: Boolean = false,
        val servidorAgora: String? = null,
    )

    /**
     * Troca o token de uso único por credenciais. Devolve true quando o
     * aparelho passou a estar provisionado nesta chamada.
     *
     * O token só é apagado depois do sucesso: se o endpoint ainda não existe
     * (backend V1) ou a rede caiu, ele continua no aparelho para a próxima
     * tentativa — jogar fora seria transformar um problema temporário numa
     * TV que precisa de visita.
     */
    fun provisionarSeNecessario(): Boolean {
        if (config.provisionado) return false
        val token = config.tokenProvisionamento?.takeIf { it.isNotBlank() } ?: return false

        return when (val resultado = api.provisionar(token)) {
            is ResultadoHttp.Ok -> {
                config.dispositivoId = resultado.valor.dispositivoId
                config.chaveAparelho = resultado.valor.chaveAparelho
                config.tokenProvisionamento = null
                config.backendV2Disponivel = true
                diario.registrar(DiarioBordo.Codigo.PROVISIONADO, "por token de uso único")
                true
            }
            is ResultadoHttp.NaoEncontrado -> {
                config.backendV2Disponivel = false
                Log.i(TAG, "backend ainda não expõe /player/provisionar")
                false
            }
            else -> {
                diario.registrar(
                    DiarioBordo.Codigo.CONFIG_FALHOU,
                    "provisionamento falhou: ${resultado.codigoDiagnostico()}",
                )
                false
            }
        }
    }

    /**
     * Manda os dados técnicos uma vez por assinatura — só reenvia quando
     * algo realmente mudou (versão do app, firmware, resolução, timezone).
     */
    fun helloSeNecessario(dados: HelloJson.Dados) {
        if (!config.temCredencial) return
        if (config.assinaturaHello == dados.assinatura()) return

        when (val resultado = api.hello(dados)) {
            is ResultadoHttp.Ok -> {
                config.assinaturaHello = dados.assinatura()
                config.backendV2Disponivel = true
                sincronizarConfigSeNecessario(resultado.valor)
            }
            is ResultadoHttp.NaoEncontrado -> config.backendV2Disponivel = false
            else -> Log.i(TAG, "hello não entregue: ${resultado.codigoDiagnostico()}")
        }
    }

    /**
     * Um ciclo de heartbeat. Devolve o que a Activity precisa aplicar na
     * thread principal — esta classe nunca toca em View.
     */
    fun heartbeat(corpo: HeartbeatJson.Corpo): Efeitos {
        if (!config.temCredencial) return Efeitos(autenticacaoFalhou = false)

        return when (val resultado = api.heartbeat(corpo)) {
            is ResultadoHttp.Ok -> {
                config.backendV2Disponivel = true
                aplicarResposta(resultado.valor)
            }
            is ResultadoHttp.ErroAutenticacao -> {
                diario.registrar(
                    DiarioBordo.Codigo.AUTH_FALHOU,
                    "heartbeat recusado (HTTP ${resultado.codigo})",
                )
                Efeitos(autenticacaoFalhou = true)
            }
            is ResultadoHttp.NaoEncontrado -> {
                config.backendV2Disponivel = false
                Efeitos()
            }
            // Servidor fora, rede caída ou limite de taxa não são falhas do
            // aparelho: não sujam o diário, que existe para o que o operador
            // precisa investigar.
            else -> Efeitos()
        }
    }

    private fun aplicarResposta(resposta: HeartbeatJson.Resposta): Efeitos {
        resposta.novaChave
            ?.takeIf { it.isNotBlank() && it != config.chaveAparelho }
            ?.let {
                config.chaveCandidata = it
                diario.registrar(DiarioBordo.Codigo.CHAVE_ROTACIONADA, "candidata recebida")
            }

        val configAplicada = sincronizarConfigSeNecessario(resposta.configVersion)

        atualizador.reavaliarAdiamento()
        atualizador.considerar(resposta.update)
        if (config.configRemota()?.politicaUpdate?.baixarAutomaticamente != false) {
            atualizador.baixarSeNecessario(resposta.update)
        }

        return Efeitos(
            atualizarPlaylist = resposta.atualizarPlaylist,
            // A margem do heartbeat é o contrato de hoje (RN-17) e continua
            // valendo; quando /config passar a mandá-la, ela vence por ser
            // aplicada depois.
            margens = if (configAplicada) config.margensOverscan else resposta.margens,
            rotacao = if (configAplicada) config.rotacaoTela else null,
            servidorAgora = resposta.servidorAgora,
        )
    }

    /**
     * Busca `/config` quando a versão do servidor difere da aplicada.
     *
     * Falhar aqui nunca pode inutilizar a tela: sem resposta, o último config
     * válido continua exatamente como estava, e a única consequência é o
     * admin mostrar "configuração pendente" até o próximo ciclo.
     */
    /**
     * Serializada (BUG-010). `GET /config` devolve sempre a config atual do
     * servidor; dois heartbeats simultâneos — possível quando um deles está
     * preso num download longo — podiam pedir a v12 e a v13 ao mesmo tempo,
     * e se a resposta da v12 chegasse por último, a tela regredia para a
     * config antiga até o próximo ciclo. Uma de cada vez, a ordem de
     * aplicação é a ordem das requisições, e cada requisição devolve algo no
     * mínimo tão novo quanto a anterior.
     *
     * Deliberadamente sem regra de "versão só sobe": se o backend um dia
     * reiniciar a numeração, essa regra travaria a tela na config velha para
     * sempre.
     */
    @Synchronized
    fun sincronizarConfigSeNecessario(versaoServidor: Int?): Boolean {
        val versao = versaoServidor ?: return false
        if (versao == config.configVersionAplicada) return false

        return when (val resultado = api.buscarConfig()) {
            is ResultadoHttp.Ok -> {
                val (remota, corpo) = resultado.valor
                config.aplicarConfigRemota(remota, corpo)
                diario.registrar(DiarioBordo.Codigo.CONFIG_APLICADA, "versão ${remota.versao}")
                true
            }
            is ResultadoHttp.NaoEncontrado -> {
                config.backendV2Disponivel = false
                false
            }
            else -> {
                diario.registrar(
                    DiarioBordo.Codigo.CONFIG_FALHOU,
                    "não foi possível buscar config: ${resultado.codigoDiagnostico()}",
                )
                false
            }
        }
    }

    private companion object {
        const val TAG = "SincronizacaoV2"
    }
}
