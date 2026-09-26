package br.com.mostrai.player.provisionamento

import br.com.mostrai.player.config.ConfigAparelho
import br.com.mostrai.player.estado.DiarioBordo
import br.com.mostrai.player.network.MostraiApi

/**
 * Troca ID da tela + código de instalação pela credencial (contrato §3).
 *
 * Bloqueante — chamar de thread de fundo.
 *
 * O código nunca é gravado: vive só nesta chamada. Se a resposta 200 se
 * perder, o contrato garante que reenviar o MESMO par em até 5 minutos
 * devolve a MESMA credencial — é isso que [ESPERAS_MS] faz, sem inventar
 * outra estratégia: só falha transitória (rede, 5xx, corpo ilegível) é
 * repetida. 400 e 401 voltam na hora para o operador; 429 espera o que o
 * servidor mandou.
 */
class Provisionador(
    private val config: ConfigAparelho,
    private val api: MostraiApi,
    private val diario: DiarioBordo,
    private val esperar: (Long) -> Unit = { Thread.sleep(it) },
) {

    sealed class Resultado {
        object Ok : Resultado()
        object IdInvalido : Resultado()
        object CodigoInvalido : Resultado()

        /** 400: o servidor recusou o formato. */
        object Invalido : Resultado()

        /** 401: inválido, expirado, cancelado ou já usado. */
        object Recusado : Resultado()

        /** 429. */
        data class Limitado(val segundos: Int) : Resultado()

        /** A repetição curta se esgotou sem resposta. */
        object SemConexao : Resultado()

        /** O servidor respondeu, mas o disco recusou a gravação: não conta como provisionado. */
        object NaoGravou : Resultado()
    }

    fun provisionar(idDigitado: String, codigoDigitado: String): Resultado {
        val id = CodigoTela.normalizar(idDigitado) ?: return Resultado.IdInvalido
        val codigo = CodigoInstalacao.normalizar(codigoDigitado) ?: return Resultado.CodigoInvalido

        var tentativa = 0
        while (true) {
            when (val resposta = api.provisionar(id, codigo)) {
                is MostraiApi.Provisionamento.Ok -> {
                    val (dispositivoId, chave) = resposta.credenciais
                    if (!config.gravarCredenciais(dispositivoId, chave)) {
                        diario.registrar(DiarioBordo.Codigo.PROVISIONAMENTO_FALHOU, "credencial não gravada")
                        return Resultado.NaoGravou
                    }
                    diario.registrar(DiarioBordo.Codigo.PROVISIONADO, dispositivoId)
                    return Resultado.Ok
                }
                is MostraiApi.Provisionamento.Invalido -> return Resultado.Invalido
                is MostraiApi.Provisionamento.Recusado -> {
                    diario.registrar(DiarioBordo.Codigo.PROVISIONAMENTO_FALHOU, "HTTP 401")
                    return Resultado.Recusado
                }
                is MostraiApi.Provisionamento.Limitado -> return Resultado.Limitado(resposta.segundos)
                is MostraiApi.Provisionamento.Transitorio -> {
                    if (tentativa >= ESPERAS_MS.size) {
                        diario.registrar(DiarioBordo.Codigo.PROVISIONAMENTO_FALHOU, resposta.motivo)
                        return Resultado.SemConexao
                    }
                    esperar(ESPERAS_MS[tentativa++])
                }
            }
        }
    }

    companion object {
        /** ~2 min 17 s no total: dentro da janela de 5 min da repetição curta. */
        val ESPERAS_MS = listOf(2_000L, 5_000L, 10_000L, 20_000L, 40_000L, 60_000L)
    }
}
