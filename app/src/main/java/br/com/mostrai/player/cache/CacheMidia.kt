package br.com.mostrai.player.cache

import android.content.Context
import android.os.SystemClock
import android.util.Log
import br.com.mostrai.player.playlist.ItemPlaylist
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache local de mídia (bloco 4 do MVP — obrigatório, não otimização: sem
 * cache, cada tela baixaria a mesma dezena de vídeos todo dia, e isso custa
 * banda demais numa internet de comércio).
 *
 * Fica em `cacheDir` (não `filesDir`) de propósito: é conteúdo
 * re-obtenível, então deixar o sistema livre para limpar sob pressão de
 * armazenamento é o comportamento certo, não um risco.
 *
 * Quando a playlist traz `contentHash` (contrato V2), o arquivo baixado é
 * verificado antes de virar cache — ver [baixarPara]. Sem hash, o
 * comportamento é o mesmo de sempre.
 */
class CacheMidia(context: Context) {

    /** Por que um item não pôde ser resolvido — alimenta o diário e o heartbeat. */
    sealed class Falha {
        data class Rede(val motivo: String) : Falha()
        data class HashDivergente(val esperado: String, val obtido: String) : Falha()
        object SemUrl : Falha()
    }

    /**
     * O resultado de uma resolução, **por chamada** (BUG-009).
     *
     * A versão anterior guardava o motivo da falha num campo compartilhado,
     * e o player o consultava depois. Entre o `resolver(A)` falhar por hash
     * e o player perguntar "posso tocar da URL?", o pré-aquecimento resolvia
     * outro item com sucesso e zerava o campo — e A, a mídia
     * comprovadamente errada, tocava da URL remota. O motivo agora viaja
     * junto com o resultado, e não há o que outra thread sobrescrever.
     */
    data class Resolucao(val arquivo: File?, val falha: Falha?) {
        /**
         * Hash divergente é mídia comprovadamente errada: tocar a URL remota
         * seria servir exatamente o arquivo que acabou de ser rejeitado.
         * Qualquer outra falha (rede, sem cache) pode cair para a URL.
         */
        val podeTocarDaUrlRemota: Boolean get() = falha !is Falha.HashDivergente
    }

    private val diretorio: File = File(context.applicationContext.cacheDir, "midia").apply {
        mkdirs()
        // Um .tmp órfão só existe se um download anterior foi interrompido no
        // meio (processo morto pelo Android) — nunca vira arquivo válido
        // porque resolver() só procura pelo nome sem sufixo. Sem isto, sobra
        // para sempre, contando no teto de tamanho sem nunca ser usado.
        listFiles { arquivo -> arquivo.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    /**
     * Uma trava por chave de cache, não uma global (BUG-008).
     *
     * O pré-aquecimento baixa a playlist inteira, um item por vez. Com trava
     * global, um item que JÁ estava no cache não conseguia começar enquanto
     * outro, completamente diferente, baixava — um vídeo grande numa
     * internet de loja pode levar minutos, e a tela ficava congelada nesse
     * tempo. Por chave, só downloads do mesmo arquivo se excluem, que é o
     * único caso em que dois escritores no mesmo `.tmp` corromperiam o cache.
     */
    private val travas = ConcurrentHashMap<String, Any>()

    /**
     * Chaves cujo download foi rejeitado por hash, e quando (BUG-006).
     *
     * O arquivo continua não batendo com o hash até alguém corrigir no
     * backend; rebaixar a cada vez que o item aparece na playlist queimava a
     * internet da loja. A rejeição vale por [SILENCIO_APOS_HASH_DIVERGENTE_MS]
     * e depois o player tenta de novo, para pegar a correção quando vier.
     * Em memória de propósito: um reinício do processo dá mais uma chance.
     */
    private val rejeitadas = ConcurrentHashMap<String, Pair<Falha.HashDivergente, Long>>()

    /**
     * Devolve o arquivo local pronto para tocar — do cache se já existir,
     * baixando primeiro se não. Sem arquivo, [Resolucao.falha] diz por quê,
     * e [Resolucao.podeTocarDaUrlRemota] diz se o player pode cair para a URL.
     *
     * Bloqueante — chamar de uma thread de fundo.
     */
    fun resolucao(item: ItemPlaylist): Resolucao {
        val url = item.url ?: return Resolucao(null, Falha.SemUrl)
        val chave = ChaveCache.paraItem(item) ?: return Resolucao(null, Falha.SemUrl)
        val arquivo = File(diretorio, chave)

        // Caminho rápido, sem trava: arquivo com o nome final sempre passou
        // pela verificação (a promoção é o último passo de baixarPara).
        if (arquivo.exists() && arquivo.length() > 0) {
            arquivo.setLastModified(System.currentTimeMillis())
            return Resolucao(arquivo, null)
        }

        rejeitadas[chave]?.let { (falha, em) ->
            if (SystemClock.elapsedRealtime() - em < SILENCIO_APOS_HASH_DIVERGENTE_MS) {
                return Resolucao(null, falha)
            }
            rejeitadas.remove(chave)
        }

        synchronized(travas.computeIfAbsent(chave) { Any() }) {
            // Outra thread pode ter baixado este mesmo arquivo enquanto esta
            // esperava a trava — não baixa de novo.
            if (arquivo.exists() && arquivo.length() > 0) return Resolucao(arquivo, null)

            val hashEsperado = item.contentHash?.takeIf { ChaveCache.ehHexSha256(it) }?.lowercase()
            return try {
                baixarPara(url, arquivo, hashEsperado)
                limitarTamanho()
                Resolucao(arquivo, null)
            } catch (e: HashDivergenteException) {
                Log.e(TAG, "mídia descartada: hash não confere ($chave)", e)
                arquivo.delete()
                val falha = Falha.HashDivergente(e.esperado, e.obtido)
                rejeitadas[chave] = falha to SystemClock.elapsedRealtime()
                Resolucao(null, falha)
            } catch (e: IOException) {
                Log.w(TAG, "falha ao baixar mídia ($chave), tocando direto da URL", e)
                arquivo.delete() // nunca deixa arquivo parcial no cache
                Resolucao(null, Falha.Rede(e.message ?: "falha de rede"))
            }
        }
    }

    /** Atalho para quem só precisa do arquivo (pré-aquecimento, testes). */
    fun resolver(item: ItemPlaylist): File? = resolucao(item).arquivo

    /**
     * Baixa de antemão os itens de uma playlist que ainda não estão em
     * cache, para não atrasar a primeira exibição de cada um. Sequencial de
     * propósito — várias baixas em paralelo saturariam a internet de um
     * comércio pequeno.
     *
     * Bloqueante — chamar de uma thread de fundo, sem bloquear a
     * reprodução em andamento.
     */
    fun preAquecer(itens: List<ItemPlaylist>) {
        for (item in itens) {
            if (item.url.isNullOrBlank()) continue
            resolver(item)
        }
    }

    private class HashDivergenteException(val esperado: String, val obtido: String) :
        IOException("SHA-256 esperado $esperado, obtido $obtido")

    /**
     * Baixa para `.tmp`, verifica e só então promove com `renameTo` — a
     * promoção é o último passo, então um arquivo com o nome final sempre
     * passou pela verificação (R9). Sem `hashEsperado`, a única verificação
     * possível é a mesma de antes (o arquivo existe e não está vazio).
     */
    @Throws(IOException::class)
    private fun baixarPara(url: String, destino: File, hashEsperado: String?) {
        val temporario = File(destino.parentFile, "${destino.name}.tmp")
        // Mesma guarda de HttpCliente.chamar: uma url de mídia com esquema
        // inesperado (não http/https) faz o cast falhar com
        // ClassCastException, não IOException — sem converter aqui, escapa
        // do catch de resolver() e derruba o app, exatamente o que essa
        // função promete nunca fazer.
        val conexao = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: ClassCastException) {
            throw IOException("URL de mídia não é http(s): $url", e)
        }
        try {
            conexao.connectTimeout = TIMEOUT_CONEXAO_MS
            conexao.readTimeout = TIMEOUT_LEITURA_MS
            conexao.connect()
            if (conexao.responseCode !in 200..299) {
                throw IOException("HTTP ${conexao.responseCode} ao baixar mídia")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(conexao.inputStream, digest).use { entrada ->
                temporario.outputStream().use { saida ->
                    entrada.copyTo(saida)
                    // ROB-004: sem isto, uma queda de energia logo depois do
                    // renameTo pode deixar o nome final apontando para dados
                    // que nunca chegaram ao disco — e o caminho rápido de
                    // resolucao() confia no arquivo com nome final para sempre.
                    saida.fd.sync()
                }
            }

            if (temporario.length() == 0L) throw IOException("arquivo baixado veio vazio")

            if (hashEsperado != null) {
                val obtido = ChaveCache.paraHex(digest.digest())
                if (obtido != hashEsperado) throw HashDivergenteException(hashEsperado, obtido)
            }

            if (!temporario.renameTo(destino)) {
                throw IOException("não foi possível mover o arquivo baixado para o cache")
            }
        } finally {
            conexao.disconnect()
            temporario.delete() // sobra só se o rename falhou ou deu exceção no meio
        }
    }

    fun tamanhoBytes(): Long = diretorio.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    fun arquivos(): Int = diretorio.listFiles()?.count { it.isFile } ?: 0

    /** Teto simples de tamanho, descarta o mais antigo — sem LRU sofisticado na v1 (CONSTRAINTS.md). */
    private fun limitarTamanho() {
        val arquivos = diretorio.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: return
        var tamanhoTotal = arquivos.sumOf { it.length() }
        if (tamanhoTotal <= TAMANHO_MAXIMO_BYTES) return

        for (arquivo in arquivos.sortedBy { it.lastModified() }) {
            if (tamanhoTotal <= TAMANHO_MAXIMO_BYTES) break
            tamanhoTotal -= arquivo.length()
            arquivo.delete()
        }
    }

    private companion object {
        const val TAG = "CacheMidia"
        const val TIMEOUT_CONEXAO_MS = 15_000
        const val TIMEOUT_LEITURA_MS = 30_000

        /** Duas tentativas por hora de uma mídia rejeitada, não uma por exibição. */
        const val SILENCIO_APOS_HASH_DIVERGENTE_MS = 30 * 60_000L

        // limite: teto fixo de 1GB, descarte por idade do arquivo (não por uso
        // real) — revisar se a operação mostrar necessidade de mais ou de LRU
        // de verdade (docs/proximas-versoes.md). Playlist de uma hora costuma
        // ter uma dezena de vídeos curtos, então a folga é generosa de propósito.
        const val TAMANHO_MAXIMO_BYTES = 1_024L * 1024 * 1024
    }
}
