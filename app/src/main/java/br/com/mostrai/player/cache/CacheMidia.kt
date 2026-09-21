package br.com.mostrai.player.cache

import android.content.Context
import android.util.Log
import br.com.mostrai.player.playlist.ItemPlaylist
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cache local de mídia (bloco 4 do MVP — obrigatório, não otimização: sem
 * cache, cada tela baixaria a mesma dezena de vídeos todo dia, e isso custa
 * banda demais numa internet de comércio).
 *
 * Fica em `cacheDir` (não `filesDir`) de propósito: é conteúdo
 * re-obtenível, então deixar o sistema livre para limpar sob pressão de
 * armazenamento é o comportamento certo, não um risco.
 */
class CacheMidia(context: Context) {

    private val diretorio: File = File(context.applicationContext.cacheDir, "midia").apply {
        mkdirs()
        // Um .tmp órfão só existe se um download anterior foi interrompido no
        // meio (processo morto pelo Android) — nunca vira arquivo válido
        // porque resolver() só procura pelo nome sem sufixo. Sem isto, sobra
        // para sempre, contando no teto de tamanho sem nunca ser usado.
        listFiles { arquivo -> arquivo.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    /**
     * Devolve o arquivo local pronto para tocar — do cache se já existir,
     * baixando primeiro se não. Retorna null se não houver como cachear
     * (sem URL, ou download falhou) — quem chama cai para tocar direto da
     * URL remota nesse caso.
     *
     * Sincronizado de propósito: sem isso, o pré-aquecimento em segundo
     * plano e a exibição que acabou de chegar no mesmo item podem baixar a
     * mesma chave ao mesmo tempo e escrever por cima uma da outra no mesmo
     * arquivo temporário, corrompendo o cache. Serializar aqui é a mesma
     * decisão que já limita `preAquecer` a uma baixa por vez.
     *
     * Bloqueante — chamar de uma thread de fundo.
     */
    @Synchronized
    fun resolver(item: ItemPlaylist): File? {
        val url = item.url ?: return null
        val chave = ChaveCache.paraItem(item) ?: return null
        val arquivo = File(diretorio, chave)

        if (arquivo.exists() && arquivo.length() > 0) {
            arquivo.setLastModified(System.currentTimeMillis())
            return arquivo
        }

        return try {
            baixarPara(url, arquivo)
            limitarTamanho()
            arquivo
        } catch (e: IOException) {
            Log.w(TAG, "falha ao baixar mídia ($chave), tocando direto da URL", e)
            arquivo.delete() // nunca deixa arquivo parcial no cache
            null
        }
    }

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

    @Throws(IOException::class)
    private fun baixarPara(url: String, destino: File) {
        val temporario = File(destino.parentFile, "${destino.name}.tmp")
        val conexao = URL(url).openConnection() as HttpURLConnection
        try {
            conexao.connectTimeout = TIMEOUT_CONEXAO_MS
            conexao.readTimeout = TIMEOUT_LEITURA_MS
            conexao.connect()
            if (conexao.responseCode !in 200..299) {
                throw IOException("HTTP ${conexao.responseCode} ao baixar mídia")
            }
            conexao.inputStream.use { entrada ->
                temporario.outputStream().use { saida -> entrada.copyTo(saida) }
            }
            if (!temporario.renameTo(destino)) {
                throw IOException("não foi possível mover o arquivo baixado para o cache")
            }
        } finally {
            conexao.disconnect()
            temporario.delete() // sobra só se o rename falhou ou deu exceção no meio
        }
    }

    /** Teto simples de tamanho, descarta o mais antigo — sem LRU sofisticado na v1 (CONSTRAINTS.md). */
    private fun limitarTamanho() {
        val arquivos = diretorio.listFiles()?.filter { it.isFile } ?: return
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

        // limite: teto fixo de 1GB, descarte por idade do arquivo (não por uso
        // real) — revisar se a operação mostrar necessidade de mais ou de LRU
        // de verdade (docs/proximas-versoes.md). Playlist de uma hora costuma
        // ter uma dezena de vídeos curtos, então a folga é generosa de propósito.
        const val TAMANHO_MAXIMO_BYTES = 1_024L * 1024 * 1024
    }
}
