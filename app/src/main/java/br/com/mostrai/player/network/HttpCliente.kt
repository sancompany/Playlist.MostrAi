package br.com.mostrai.player.network

import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Wrapper fino sobre HttpURLConnection.
 *
 * Sem dependência externa de propósito: a frequência de chamadas deste app
 * (poll a cada 15 min, played em lote, heartbeat a cada 5 min) não pede o
 * que um cliente HTTP maior ofereceria, e cada dependência a menos é uma
 * fonte a menos de problema de resolução numa TV com internet de comércio.
 */
class HttpCliente(
    private val timeoutConexaoMs: Int = 10_000,
    private val timeoutLeituraMs: Int = 15_000,
) {
    data class Resposta(val codigo: Int, val corpo: String, val cabecalhos: Map<String, List<String>>)

    @Throws(IOException::class)
    fun get(url: String, cabecalhos: Map<String, String>): Resposta =
        chamar("GET", url, cabecalhos, null)

    @Throws(IOException::class)
    fun post(url: String, cabecalhos: Map<String, String>, corpo: String): Resposta =
        chamar("POST", url, cabecalhos + ("Content-Type" to "application/json; charset=utf-8"), corpo)

    @Throws(IOException::class)
    private fun chamar(metodo: String, url: String, cabecalhos: Map<String, String>, corpo: String?): Resposta {
        val conexao = URL(url).openConnection() as HttpURLConnection
        try {
            conexao.requestMethod = metodo
            conexao.connectTimeout = timeoutConexaoMs
            conexao.readTimeout = timeoutLeituraMs
            cabecalhos.forEach { (chave, valor) -> conexao.setRequestProperty(chave, valor) }

            if (corpo != null) {
                conexao.doOutput = true
                OutputStreamWriter(conexao.outputStream, Charsets.UTF_8).use { it.write(corpo) }
            }

            val codigo = conexao.responseCode
            val fluxo = if (codigo in 200..299) conexao.inputStream else conexao.errorStream
            val texto = fluxo?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Resposta(codigo, texto, conexao.headerFields ?: emptyMap())
        } finally {
            conexao.disconnect()
        }
    }
}
