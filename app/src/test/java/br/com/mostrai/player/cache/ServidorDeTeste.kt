package br.com.mostrai.player.cache

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Servidor HTTP mínimo para os testes de download.
 *
 * `com.sun.net.httpserver` não existe no classpath de teste do Android, e
 * puxar uma biblioteca só para isto contrariaria a mesma decisão que mantém
 * `HttpURLConnection` na produção. Trinta linhas de `ServerSocket` respondem
 * o que estes testes precisam: um corpo, um código, e a possibilidade de
 * trocar os dois entre um caso e outro.
 */
class ServidorDeTeste {

    @Volatile
    var corpo: ByteArray = ByteArray(0)

    @Volatile
    var codigo: Int = 200

    private val socket = ServerSocket(0)

    @Volatile
    private var rodando = true

    val baseUrl: String get() = "http://127.0.0.1:${socket.localPort}"

    init {
        thread(isDaemon = true) {
            while (rodando) {
                val conexao = runCatching { socket.accept() }.getOrNull() ?: continue
                runCatching {
                    conexao.use { cliente ->
                        // Consome a requisição até a linha em branco; o
                        // conteúdo dela não importa para estes testes.
                        val leitor = BufferedReader(InputStreamReader(cliente.getInputStream()))
                        while (true) {
                            val linha = leitor.readLine() ?: break
                            if (linha.isEmpty()) break
                        }

                        val corpoAtual = corpo
                        val cabecalho = buildString {
                            append("HTTP/1.1 $codigo ${if (codigo == 200) "OK" else "Erro"}\r\n")
                            append("Content-Length: ${corpoAtual.size}\r\n")
                            append("Connection: close\r\n\r\n")
                        }
                        cliente.getOutputStream().apply {
                            write(cabecalho.toByteArray())
                            write(corpoAtual)
                            flush()
                        }
                    }
                }
            }
        }
    }

    fun encerrar() {
        rodando = false
        runCatching { socket.close() }
    }
}
