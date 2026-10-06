package br.com.mostrai.player.atualizacao

import android.content.Context
import java.io.File

/**
 * Estado do updater USB **do processo**: some num reinício (de propósito —
 * "Depois" vale até o pendrive sair ou a TV reiniciar). Lido pelo bloco
 * técnico.
 */
object EstadoAtualizacao {

    data class Candidato(val versionName: String?, val versionCode: Long, val arquivo: File, val volumeId: String)

    /** Pendrives montados agora (pelo último levantamento). */
    @Volatile var conectados: Set<String> = emptySet()
        private set

    @Volatile var ultimaVerificacaoMs: Long? = null
    @Volatile var ultimoResultado: String? = null
    @Volatile var versaoEncontrada: String? = null
    @Volatile var candidato: Candidato? = null

    private val dispensados = mutableSetOf<String>()
    private val verificados = mutableSetOf<String>()

    /**
     * Atualiza os pendrives montados. Devolve os que apareceram desde o
     * último levantamento (a verificar). Pendrive que saiu volta a poder ser
     * oferecido quando reconectado.
     */
    @Synchronized
    fun registrarMontados(ids: Set<String>): Set<String> {
        val saiu = conectados - ids
        dispensados.removeAll(saiu)
        verificados.removeAll(saiu)
        conectados = ids
        return ids - verificados
    }

    @Synchronized
    fun marcarVerificado(id: String) {
        verificados += id
    }

    /** O acesso a este pendrive acabou de ser concedido: verificar de novo. */
    @Synchronized
    fun reverificar(id: String) {
        verificados -= id
        dispensados -= id
    }

    /** Depois de liberar "instalar apps" nas Configurações, reabrir a oferta ao voltar. */
    @Volatile var retomarAoVoltar: Boolean = false

    /** "Verificar USB" do técnico: tudo que está montado volta a ser verificado e oferecido. */
    @Synchronized
    fun esquecerVerificacoes() {
        verificados.clear()
        dispensados.clear()
    }

    @Synchronized
    fun dispensar(volumeId: String) {
        dispensados += volumeId
    }

    @Synchronized
    fun foiDispensado(volumeId: String): Boolean = volumeId in dispensados

    /** Só para teste. */
    @Synchronized
    fun reiniciar() {
        conectados = emptySet()
        ultimaVerificacaoMs = null
        ultimoResultado = null
        versaoEncontrada = null
        candidato = null
        retomarAoVoltar = false
        dispensados.clear()
        verificados.clear()
    }

    // ---------------------------------------------- instalação em andamento

    private const val ARQUIVO = "mostrai_atualizacao"
    private const val CHAVE_PENDENTE = "instalacao_pendente_code"
    private const val CHAVE_ULTIMA = "ultima_atualizacao"

    /** O instalador foi aberto para esta versão; ao voltar, se ela não entrou, foi cancelada ou recusada. */
    fun marcarInstalacaoIniciada(context: Context, versionCode: Long) {
        prefs(context).edit().putLong(CHAVE_PENDENTE, versionCode).commit()
    }

    /** Ao voltar à frente: `true` se havia instalação iniciada que não entrou (o Player continua na versão atual). */
    fun instalacaoNaoConcluida(context: Context, versionCodeAtual: Long): Boolean {
        val pendente = prefs(context).getLong(CHAVE_PENDENTE, -1L)
        if (pendente < 0) return false
        prefs(context).edit().remove(CHAVE_PENDENTE).apply()
        return versionCodeAtual < pendente
    }

    fun registrarAtualizado(context: Context, descricao: String) {
        prefs(context).edit().remove(CHAVE_PENDENTE).putString(CHAVE_ULTIMA, descricao).commit()
    }

    fun ultimaAtualizacao(context: Context): String? = prefs(context).getString(CHAVE_ULTIMA, null)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)
}
