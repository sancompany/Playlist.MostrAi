package br.com.mostrai.player.provisionamento

/**
 * ID humano da tela (contrato §2): `M-` + o id com no mínimo 4 dígitos.
 * Mesma regra de `normalizarCodigoTela` do backend
 * (`src/lib/codigo-tela.js`): espaços e hífens ignorados, `M` opcional, 1 a
 * 10 dígitos, id entre 1 e 2³¹−1. Não é segredo.
 */
object CodigoTela {

    private const val TETO_INT4 = 2_147_483_647L
    private val FORMATO = Regex("^M?(\\d{1,10})$")

    /** `235`, `0235`, `m0235`, `M-0235` → `M-0235`; qualquer outra coisa → null. */
    fun normalizar(entrada: String): String? {
        val limpo = entrada.trim().uppercase().replace(Regex("[\\s-]"), "")
        val digitos = FORMATO.matchEntire(limpo)?.groupValues?.get(1) ?: return null
        val id = digitos.toLong()
        if (id < 1 || id > TETO_INT4) return null
        return "M-" + id.toString().padStart(4, '0')
    }
}

/**
 * Código de instalação (contrato §3): 8 caracteres do alfabeto sem
 * ambíguos, exibido `XXXX-XXXX`, case-insensitive, espaços e hífen
 * ignorados. Temporário e de uso único — nunca é gravado no aparelho.
 */
object CodigoInstalacao {

    const val ALFABETO = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    const val TAMANHO = 8

    /** `7k4m9q2w`, `7K4M-9Q2W` → `7K4M-9Q2W`; fora do alfabeto ou do tamanho → null. */
    fun normalizar(entrada: String): String? {
        val limpo = entrada.trim().uppercase().replace(Regex("[\\s-]"), "")
        if (limpo.length != TAMANHO || limpo.any { it !in ALFABETO }) return null
        return limpo.substring(0, 4) + "-" + limpo.substring(4)
    }

    /** Formata o que já foi digitado, mesmo incompleto: `7K4M9` → `7K4M-9`. */
    fun formatarParcial(digitado: String): String =
        if (digitado.length <= 4) digitado else digitado.substring(0, 4) + "-" + digitado.substring(4)
}
