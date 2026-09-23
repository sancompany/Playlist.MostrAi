package br.com.mostrai.player.cache

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Servidor HTTP mínimo para testes que precisam de rede de verdade.
 *
 * `com.sun.net.httpserver` não existe no classpath de teste do Android, e
 * puxar uma biblioteca só para isto contrariaria a mesma decisão que mantém
 * `HttpURLConnection` na produção.
 *
 * Por padrão responde [corpo]/[codigo] para qualquer caminho. [rotas]
 * sobrepõe por prefixo de caminho, e [trava] segura a resposta de um prefixo
 * até alguém liberar — é o que permite reproduzir corridas de forma
 * determinística (uma requisição "em voo" enquanto o teste faz outra coisa).
 */
class ServidorDeTeste {

    data class Resposta(val codigo: Int = 200, val corpo: ByteArray = ByteArray(0))

    @Volatile
    var corpo: ByteArray = ByteArray(0)

    @Volatile
    var codigo: Int = 200

    /** Prefixo de caminho → resposta. O prefixo mais longo que casar vence. */
    val rotas = ConcurrentHashMap<String, Resposta>()

    /** Prefixo de caminho → trava que segura a resposta até `countDown()`. */
    val travas = ConcurrentHashMap<String, CountDownLatch>()

    /** Cada requisição recebida, como "METODO /caminho", na ordem de chegada. */
    val recebidas = CopyOnWriteArrayList<String>()

    private val socket = ServerSocket(0)

    @Volatile
    private var rodando = true

    val baseUrl: String get() = "http://127.0.0.1:${socket.localPort}"

    fun contar(prefixo: String): Int = recebidas.count { it.substringAfter(' ').startsWith(prefixo) }

    init {
        thread(isDaemon = true) {
            while (rodando) {
                val conexao = runCatching { socket.accept() }.getOrNull() ?: continue
                thread(isDaemon = true) { atender(conexao) }
            }
        }
    }

    private fun atender(conexao: java.net.Socket) {
        runCatching {
            conexao.use { cliente ->
                val leitor = BufferedReader(InputStreamReader(cliente.getInputStream()))
                val primeira = leitor.readLine() ?: return
                var tamanhoCorpo = 0
                while (true) {
                    val linha = leitor.readLine() ?: break
                    if (linha.isEmpty()) break
                    if (linha.startsWith("Content-Length:", ignoreCase = true)) {
                        tamanhoCorpo = linha.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }
                repeat(tamanhoCorpo) { leitor.read() }

                val partes = primeira.split(" ")
                val metodo = partes.getOrElse(0) { "" }
                val caminho = partes.getOrElse(1) { "" }
                recebidas += "$metodo $caminho"

                travas.entries
                    .filter { caminho.startsWith(it.key) }
                    .maxByOrNull { it.key.length }
                    ?.value?.await(30, TimeUnit.SECONDS)

                val resposta = rotas.entries
                    .filter { caminho.startsWith(it.key) }
                    .maxByOrNull { it.key.length }
                    ?.value ?: Resposta(codigo, corpo)

                val cabecalho = buildString {
                    append("HTTP/1.1 ${resposta.codigo} ${if (resposta.codigo in 200..299) "OK" else "Erro"}\r\n")
                    append("Content-Length: ${resposta.corpo.size}\r\n")
                    append("Connection: close\r\n\r\n")
                }
                cliente.getOutputStream().apply {
                    write(cabecalho.toByteArray())
                    write(resposta.corpo)
                    flush()
                }
            }
        }
    }

    fun encerrar() {
        rodando = false
        travas.values.forEach { while (it.count > 0) it.countDown() }
        runCatching { socket.close() }
    }
}
