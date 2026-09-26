package br.com.mostrai.player.cache

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

    data class Resposta(
        val codigo: Int = 200,
        val corpo: ByteArray = ByteArray(0),
        val tipo: String? = null,
        val cabecalhos: Map<String, String> = emptyMap(),
    ) {
        constructor(codigo: Int, corpo: String) : this(codigo, corpo.toByteArray())
    }

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

    /** Requisição completa, para conferir o contrato: cabeçalhos (em minúsculas) e corpo. */
    data class Requisicao(val metodo: String, val caminho: String, val cabecalhos: Map<String, String>, val corpo: String)

    val detalhadas = CopyOnWriteArrayList<Requisicao>()

    fun ultima(prefixo: String): Requisicao? = detalhadas.lastOrNull { it.caminho.startsWith(prefixo) }

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
                // Bytes, não caracteres: Content-Length conta bytes, e um
                // corpo com acento (o heartbeat leva a mensagem do diário)
                // deixava um Reader esperando caracteres que nunca chegavam.
                val entrada = cliente.getInputStream()
                fun linha(): String? {
                    val bytes = java.io.ByteArrayOutputStream()
                    while (true) {
                        val b = entrada.read()
                        if (b < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.UTF_8.name())
                        if (b == '\n'.code) return bytes.toString(Charsets.UTF_8.name()).trimEnd('\r')
                        bytes.write(b)
                    }
                }
                val primeira = linha() ?: return
                var tamanhoCorpo = 0
                val cabecalhos = mutableMapOf<String, String>()
                while (true) {
                    val cabecalho = linha() ?: break
                    if (cabecalho.isEmpty()) break
                    cabecalhos[cabecalho.substringBefore(':').trim().lowercase()] = cabecalho.substringAfter(':').trim()
                    if (cabecalho.startsWith("Content-Length:", ignoreCase = true)) {
                        tamanhoCorpo = cabecalho.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }
                val corpoRecebido = java.io.ByteArrayOutputStream()
                var restante = tamanhoCorpo
                val bloco = ByteArray(4096)
                while (restante > 0) {
                    val lidos = entrada.read(bloco, 0, minOf(restante, bloco.size))
                    if (lidos < 0) break
                    corpoRecebido.write(bloco, 0, lidos)
                    restante -= lidos
                }

                val partes = primeira.split(" ")
                val metodo = partes.getOrElse(0) { "" }
                val caminho = partes.getOrElse(1) { "" }
                detalhadas += Requisicao(metodo, caminho, cabecalhos, corpoRecebido.toString(Charsets.UTF_8.name()))
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
                    resposta.tipo?.let { append("Content-Type: $it\r\n") }
                    resposta.cabecalhos.forEach { (nome, valor) -> append("$nome: $valor\r\n") }
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
