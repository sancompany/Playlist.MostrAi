package br.com.mostrai.player.ciclo

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import br.com.mostrai.player.PlayerActivity
import br.com.mostrai.player.cache.ServidorDeTeste
import br.com.mostrai.player.config.ConfigAparelho
import br.com.mostrai.player.estado.DiarioBordo
import br.com.mostrai.player.proof.ProofOfPlayDb
import java.io.File
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Sobe o `PlayerActivity` inteiro contra um [ServidorDeTeste] local.
 *
 * É o que permite provar defeitos de lifecycle e concorrência no
 * orquestrador em vez de só argumentar sobre eles: a Activity faz requisições
 * HTTP de verdade, grava na fila SQLite de verdade, e o teste controla o
 * relógio do looper principal e quando cada resposta do servidor sai.
 */
class Harness {

    val contexto: Context = ApplicationProvider.getApplicationContext()
    val servidor = ServidorDeTeste()

    init {
        limparEstado()
    }

    fun limparEstado() {
        listOf(
            "mostrai_config", "mostrai_watchdog", "mostrai_cache_playlist",
            ProofOfPlayDb.PREFS_PERDAS,
        ).forEach { contexto.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        contexto.deleteDatabase(ProofOfPlayDb.NOME_ARQUIVO)
        contexto.deleteDatabase(DiarioBordo.NOME_ARQUIVO)
        File(contexto.cacheDir, "midia").deleteRecursively()
        pularIntroducao()
    }

    fun provisionar() {
        ConfigAparelho(contexto).apply {
            baseUrl = servidor.baseUrl
            dispositivoId = "tela-1"
            chaveAparelho = "chave-teste"
        }
    }

    /**
     * O vídeo de abertura precisa de decoder, que o Robolectric não tem. O
     * estado estático "já tocou" é o mesmo que um retorno do painel
     * encontraria, então pulá-lo não muda o caminho testado.
     */
    fun pularIntroducao() {
        val campo = PlayerActivity::class.java.getDeclaredField("introJaTocou")
        campo.isAccessible = true
        campo.setBoolean(null, true)
    }

    fun playlistComUmVideo(duracao: Int = 10, contentHash: String? = null): String {
        val hash = contentHash?.let { ""","contentHash":"$it"""" } ?: ""
        return """
            {"versaoContrato":1,"janelaId":"j1","janelaInicio":null,"servidorAgora":null,
             "itens":[{"itemProgramacaoId":"i1","criativoId":"c1","duracaoSegundos":$duracao,
                       "url":"${servidor.baseUrl}/midia/v.mp4","anuncianteId":"a1",
                       "autoanuncio":false,"institucional":false,"contabiliza":true$hash}]}
        """.trimIndent()
    }

    fun subir(): ActivityController<PlayerActivity> {
        val controle = Robolectric.buildActivity(PlayerActivity::class.java).setup()
        idle()
        return controle
    }

    fun idle() = shadowOf(Looper.getMainLooper()).idle()

    fun avancar(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))

    /**
     * Corrotinas em `Dispatchers.IO` rodam em thread de verdade; o looper
     * principal está pausado. Alterna os dois até a condição valer.
     */
    fun esperar(timeoutMs: Long = 10_000, condicao: () -> Boolean) {
        val limite = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < limite) {
            idle()
            if (condicao()) return
            Thread.sleep(20)
        }
        idle()
        check(condicao()) { "condição não foi atingida em ${timeoutMs}ms" }
    }

    /** Linhas na fila que começaram e nunca terminaram. */
    fun orfaos(): Int {
        val db = ProofOfPlayDb(contexto).readableDatabase
        db.rawQuery("SELECT COUNT(*) FROM ${ProofOfPlayDb.TABELA} WHERE terminado_em IS NULL", null).use {
            it.moveToFirst()
            return it.getInt(0)
        }
    }

    fun encerrar() = servidor.encerrar()
}
