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
 * Fica em `filesDir`, não em `cacheDir` (Ponto Móvel, 02/10/2026): o
 * Android apaga `cacheDir` sozinho quando falta espaço, e uma tela que passa
 * dias sem internet não tem como baixar de novo o que sumiu. Quem decide o
 * que sai é [limitarTamanho], que nunca remove mídia protegida
 * ([proteger]: a programação em vigor e o institucional).
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

        /**
         * O aparelho não alcança a internet (DNS, conexão recusada, tempo de
         * conexão esgotado). A URL remota é igualmente inalcançável: tentar
         * tocá-la só deixa a tela preta por dezenas de segundos.
         */
        data class SemRede(val motivo: String) : Falha()

        /** Disco sem espaço mesmo depois de liberar o que não é protegido. */
        object SemEspaco : Falha()
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
        val podeTocarDaUrlRemota: Boolean get() = falha !is Falha.HashDivergente && falha !is Falha.SemRede
    }

    private val diretorio: File = File(context.applicationContext.filesDir, "midia").apply {
        mkdirs()
        migrarDoCacheAntigo(File(context.applicationContext.cacheDir, "midia"), this)
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
     * Até quando (relógio monotônico) não vale a pena tentar a rede: a
     * última tentativa nem conectou. Sem isto, com a internet fora, cada
     * item não baixado custava uma tentativa de conexão inteira a cada volta
     * da playlist — e a tela esperando.
     */
    @Volatile
    private var semRedeAte = 0L

    /** Chaves que a limpeza nunca remove: programação em vigor e institucional. */
    @Volatile
    private var protegidas: Set<String> = emptySet()

    /** Espaço do disco onde o cache mora — trocável só em teste. */
    internal var espacoLivre: () -> Long = { MedidorDeDisco.livre(diretorio) }
    internal var espacoTotal: () -> Long = { MedidorDeDisco.total(diretorio) }

    /** A rede voltou: a próxima resolução tenta baixar sem esperar o silêncio. */
    fun redeVoltou() {
        semRedeAte = 0L
    }

    /** Substitui o conjunto protegido pelos itens com mídia que ainda podem tocar. */
    fun proteger(itens: Collection<ItemPlaylist>) {
        protegidas = itens.mapNotNullTo(mutableSetOf()) { if (it.url.isNullOrBlank()) null else ChaveCache.paraItem(it) }
    }

    /** Barato e sem trava: o arquivo final só existe depois de verificado. */
    fun emCache(item: ItemPlaylist): Boolean {
        if (item.url.isNullOrBlank()) return false
        val chave = ChaveCache.paraItem(item) ?: return false
        val arquivo = File(diretorio, chave)
        return arquivo.exists() && arquivo.length() > 0
    }

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
        if (SystemClock.elapsedRealtime() < semRedeAte) return Resolucao(null, Falha.SemRede("sem rede recente"))

        synchronized(travas.computeIfAbsent(chave) { Any() }) {
            // Outra thread pode ter baixado este mesmo arquivo enquanto esta
            // esperava a trava — não baixa de novo.
            if (arquivo.exists() && arquivo.length() > 0) return Resolucao(arquivo, null)

            val hashEsperado = item.contentHash?.takeIf { ChaveCache.ehHexSha256(it) }?.lowercase()
            if (!garantirEspaco()) return Resolucao(null, Falha.SemEspaco)
            return try {
                baixarPara(url, arquivo, hashEsperado)
                limitarTamanho()
                Resolucao(arquivo, null)
            } catch (e: SemEspacoNoDownload) {
                arquivo.delete()
                Log.w(TAG, "download interrompido para manter a reserva de disco ($chave)")
                Resolucao(null, Falha.SemEspaco)
            } catch (e: HashDivergenteException) {
                Log.e(TAG, "mídia descartada: hash não confere ($chave)", e)
                arquivo.delete()
                val falha = Falha.HashDivergente(e.esperado, e.obtido)
                rejeitadas[chave] = falha to SystemClock.elapsedRealtime()
                Resolucao(null, falha)
            } catch (e: IOException) {
                arquivo.delete() // nunca deixa arquivo parcial no cache
                if (ehFaltaDeRede(e)) {
                    Log.w(TAG, "sem rede para baixar mídia ($chave)")
                    semRedeAte = SystemClock.elapsedRealtime() + SILENCIO_SEM_REDE_MS
                    Resolucao(null, Falha.SemRede(e.javaClass.simpleName))
                } else {
                    Log.w(TAG, "falha ao baixar mídia ($chave), tocando direto da URL", e)
                    Resolucao(null, Falha.Rede(e.message ?: "falha de rede"))
                }
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
            throw IOException("URL de mídia não é http(s): esquema ${url.substringBefore(':').take(16)}", e)
        }
        try {
            conexao.connectTimeout = TIMEOUT_CONEXAO_MS
            conexao.readTimeout = TIMEOUT_LEITURA_MS
            // Só a falha de CONEXÃO é "sem rede". Um timeout de leitura no
            // meio da transferência é servidor lento com rede de pé: o
            // streaming direto ainda pode funcionar, e silenciar todos os
            // downloads por 60 s pularia anúncios à toa.
            try {
                conexao.connect()
            } catch (e: IOException) {
                throw SemConexao(e)
            }
            if (conexao.responseCode !in 200..299) {
                throw IOException("HTTP ${conexao.responseCode} ao baixar mídia")
            }
            // BUG-020: portal cativo de Wi-Fi e proxy respondem 200 com uma
            // página para qualquer URL. Sem contentHash (V1), nada mais
            // confere o conteúdo, e a página virava o "vídeo" do criativo
            // para sempre. Mídia nunca é HTML ou JSON. `text/plain` NÃO entra
            // (BUG-029): é o padrão de armazenamentos como o Supabase Storage
            // quando o upload não informa o tipo, e recusá-lo desligava o
            // cache da frota inteira.
            if (naoEhMidia(conexao.contentType)) {
                throw IOException("resposta não é mídia (${conexao.contentType})")
            }

            // A reserva vale DURANTE o download, não só antes dele: um vídeo
            // de 600 MB começado com a reserva no limite enchia o disco que a
            // fila de comprovantes e o sistema precisam. Tamanho anunciado:
            // abre espaço antes (ou desiste); sem ele, confere a cada bloco.
            val anunciado = conexao.contentLengthLong
            if (anunciado > 0 && !garantirEspaco(anunciado)) throw SemEspacoNoDownload()

            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(conexao.inputStream, digest).use { entrada ->
                temporario.outputStream().use { saida ->
                    val bloco = ByteArray(64 * 1024)
                    var desdeConferencia = 0L
                    while (true) {
                        val lidos = entrada.read(bloco)
                        if (lidos < 0) break
                        saida.write(bloco, 0, lidos)
                        desdeConferencia += lidos
                        if (desdeConferencia >= CONFERENCIA_DE_ESPACO_BYTES) {
                            desdeConferencia = 0L
                            if (espacoLivre() < reservaBytes()) throw SemEspacoNoDownload()
                        }
                    }
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

    private fun naoEhMidia(tipo: String?): Boolean {
        val t = tipo?.lowercase() ?: return false
        return "html" in t || "json" in t
    }

    fun tamanhoBytes(): Long = diretorio.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    fun arquivos(): Int = diretorio.listFiles()?.count { it.isFile } ?: 0

    /** Quantos itens com mídia desta lista já estão prontos para tocar sem rede. */
    fun disponiveis(itens: Collection<ItemPlaylist>): Pair<Int, Int> {
        val comMidia = itens.filter { !it.url.isNullOrBlank() }.distinctBy { ChaveCache.paraItem(it) }
        return comMidia.count(::emCache) to comMidia.size
    }

    /** Folga que o disco precisa manter: o sistema e a fila de comprovantes vêm antes do vídeo. */
    fun reservaBytes(): Long = maxOf(RESERVA_MINIMA_BYTES, espacoTotal() / 10)

    /** Espaço livre suficiente para mais um download, liberando o que não é protegido. */
    private fun garantirEspaco(aBaixar: Long = 0L): Boolean {
        if (espacoLivre() - aBaixar >= reservaBytes()) return true
        limitarTamanho(aBaixar)
        return espacoLivre() - aBaixar >= reservaBytes()
    }

    /**
     * Sem teto fixo pequeno (Ponto Móvel): o limite é o que o disco aguenta
     * mantendo a reserva livre. Sai primeiro o que não é protegido, do mais
     * antigo uso para o mais recente; mídia protegida nunca sai — se só
     * sobrou ela, a limpeza para e o download seguinte falha com
     * [Falha.SemEspaco], em vez de apagar o que a programação ainda vai tocar.
     */
    private fun limitarTamanho(aBaixar: Long = 0L) {
        val arquivos = diretorio.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: return
        var falta = reservaBytes() + aBaixar - espacoLivre()
        if (falta <= 0) return
        val protegidasAgora = protegidas
        for (arquivo in arquivos.filter { it.name !in protegidasAgora }.sortedBy { it.lastModified() }) {
            if (falta <= 0) break
            falta -= arquivo.length()
            arquivo.delete()
        }
    }

    private fun ehFaltaDeRede(e: IOException): Boolean = generateSequence<Throwable>(e) { it.cause }.any {
        it is SemConexao || it is java.net.UnknownHostException || it is java.net.ConnectException ||
            it is java.net.NoRouteToHostException
    }

    /** O download chegaria (ou chegou) na reserva de disco: desiste e apaga o parcial. */
    private class SemEspacoNoDownload : IOException("reserva de disco atingida")

    /** A conexão nem abriu (DNS, rota, recusa, timeout de conexão). */
    private class SemConexao(causa: IOException) : IOException(causa.message, causa)

    private companion object {
        const val TAG = "CacheMidia"
        const val TIMEOUT_CONEXAO_MS = 15_000
        const val TIMEOUT_LEITURA_MS = 30_000

        /** Duas tentativas por hora de uma mídia rejeitada, não uma por exibição. */
        const val SILENCIO_APOS_HASH_DIVERGENTE_MS = 30 * 60_000L

        /** Depois de uma tentativa que nem conectou, a rede só é tentada de novo depois disto. */
        const val SILENCIO_SEM_REDE_MS = 60_000L

        /** De quanto em quanto o download confere se ainda está fora da reserva. */
        const val CONFERENCIA_DE_ESPACO_BYTES = 8L * 1024 * 1024

        // limite: reserva de 512 MB ou 10% do disco, o que for maior — o
        // resto é do cache. Revisar com a TCL real (espaço livre medido no
        // checklist físico) se a programação da janela não couber.
        const val RESERVA_MINIMA_BYTES = 512L * 1024 * 1024

        /**
         * Versões até 2.0.0 guardavam a mídia em `cacheDir`. Move em vez de
         * baixar de novo — a TV pode estar sem internet na primeira abertura
         * da versão nova. Mesmo volume (/data), então é só um rename.
         */
        private fun migrarDoCacheAntigo(antigo: File, novo: File) {
            val arquivos = antigo.listFiles() ?: return
            for (arquivo in arquivos) {
                if (!arquivo.isFile || arquivo.name.endsWith(".tmp")) {
                    arquivo.delete()
                    continue
                }
                val destino = File(novo, arquivo.name)
                if (destino.exists() || !arquivo.renameTo(destino)) arquivo.delete()
            }
            antigo.delete()
        }
    }
}

/**
 * Como o cache mede o disco. Trocável só em teste: os testes de ciclo
 * sobem a Activity inteira, e o resultado não pode depender de quanto
 * espaço sobrou na máquina que roda a suíte.
 */
internal object MedidorDeDisco {
    @Volatile var livre: (File) -> Long = { it.usableSpace }
    @Volatile var total: (File) -> Long = { it.totalSpace }

    fun padrao() {
        livre = { it.usableSpace }
        total = { it.totalSpace }
    }
}
