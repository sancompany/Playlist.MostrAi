package br.com.mostrai.player.ciclo

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import br.com.mostrai.player.HostDaApi
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
 * A Activity faz requisições HTTP de verdade (para o servidor local, via
 * [HostDaApi], que só é mutável na variante debug), grava na fila SQLite de
 * verdade, e o teste controla o relógio do looper principal e quando cada
 * resposta sai.
 */
class Harness {

    val contexto: Context = ApplicationProvider.getApplicationContext()
    val servidor = ServidorDeTeste()

    init {
        // Disco folgado e fixo: 100 GB, 50 livres — a reserva nunca depende
        // da máquina que roda o teste.
        br.com.mostrai.player.cache.MedidorDeDisco.livre = { 50L shl 30 }
        br.com.mostrai.player.cache.MedidorDeDisco.total = { 100L shl 30 }
        limparEstado()
        HostDaApi.base = servidor.baseUrl
        // Heartbeat responde "nada a fazer" até o teste dizer outra coisa.
        servidor.rotas["/player/"] = ServidorDeTeste.Resposta(200, """{"configVersion":0,"playlist":{"atualizar":false}}""")
    }

    fun limparEstado() {
        listOf(
            ConfigAparelho.ARQUIVO, "mostrai_watchdog", "mostrai_cache_playlist", ProofOfPlayDb.PREFS_PERDAS,
            "mostrai_relogio", "mostrai_institucional",
            "mostrai_atualizacao", "mostrai_atualizacao_usb", "mostrai_ligar_tela",
        ).forEach { contexto.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        contexto.deleteDatabase(ProofOfPlayDb.NOME_ARQUIVO)
        contexto.deleteDatabase(DiarioBordo.NOME_ARQUIVO)
        contexto.deleteDatabase(br.com.mostrai.player.operacao.RegistroOperacional.NOME_ARQUIVO)
        contexto.getSharedPreferences(br.com.mostrai.player.operacao.RegistroOperacional.ARQUIVO_BOOT, Context.MODE_PRIVATE).edit().clear().commit()
        File(contexto.cacheDir, "midia").deleteRecursively()
        File(contexto.filesDir, "midia").deleteRecursively()
        File(contexto.filesDir, "atualizacao").deleteRecursively()
        br.com.mostrai.player.atualizacao.EstadoAtualizacao.reiniciar()
        br.com.mostrai.player.atualizacao.ApkFalso.esquecerFileProvider()
        // Sem pendrive nenhum até o teste montar um (o Robolectric não tem USB).
        br.com.mostrai.player.atualizacao.FontesAtualizacao.padrao()
        br.com.mostrai.player.atualizacao.FontesAtualizacao.volumes = { br.com.mostrai.player.atualizacao.FonteVolumes { emptyList() } }
        pularIntroducao()
    }

    fun provisionar(id: String = "M-0001", chave: String = "chave-teste") {
        check(ConfigAparelho(contexto).gravarCredenciais(id, chave))
    }

    /**
     * O vídeo de abertura precisa de decoder, que o Robolectric não tem. O
     * estado estático "já tocou" é o mesmo de uma Activity recriada no mesmo
     * processo, então pulá-lo não muda o caminho testado.
     */
    fun pularIntroducao() {
        PlayerActivity.introJaTocou = true
    }

    /**
     * Playlist como o servidor manda: janela da hora corrente, com início,
     * fim e o relógio do servidor. Sem isso o Player não pode autorizar
     * comercial (offline não autoriza veiculação).
     */
    fun playlistComUmVideo(
        duracao: Int = 10,
        contentHash: String? = null,
        janela: String = "j1",
        agoraMs: Long = System.currentTimeMillis(),
    ): String {
        val hash = contentHash?.let { ""","contentHash":"$it"""" } ?: ""
        val hora = 3_600_000L
        val inicio = java.time.Instant.ofEpochMilli(Math.floorDiv(agoraMs, hora) * hora)
        val fim = inicio.plusMillis(hora)
        val agora = java.time.Instant.ofEpochMilli(agoraMs)
        return """
            {"versaoContrato":2,"janelaId":"$janela","janelaInicio":"$inicio","janelaFim":"$fim","servidorAgora":"$agora",
             "itens":[{"itemProgramacaoId":"i1","criativoId":"c1","duracaoSegundos":$duracao,
                       "url":"${servidor.baseUrl}/midia/v.mp4","anuncianteId":17,
                       "autoanuncio":false,"institucional":false,"contabiliza":true$hash}]}
        """.trimIndent()
    }

    /**
     * Mídia que o ExoPlayer do Robolectric nunca recebe: o download do cache
     * falha (503) e a leitura direta da URL pelo ExoPlayer fica pendurada. O
     * item fica "no ar" (PLAYING, exibição registrada) sem que a falta de
     * decoder no Robolectric transforme tudo em erro de reprodução.
     */
    fun midiaNoAr() {
        servidor.rotas["/midia"] = ServidorDeTeste.Resposta(503, "")
        servidor.rotasPorCabecalho["/midia"] = "icy-metadata" to ServidorDeTeste.Resposta(pendurar = true)
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
        check(condicao()) { "condição não foi atingida em ${timeoutMs}ms; servidor recebeu ${servidor.recebidas}" }
    }

    /** Deixa as corrotinas de IO e o looper andarem por [ms] reais, sem condição. */
    fun deixarRodar(ms: Long) {
        val limite = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < limite) {
            idle()
            Thread.sleep(20)
        }
        idle()
    }

    /** Linhas na fila que começaram e nunca terminaram. */
    fun orfaos(): Int {
        val db = ProofOfPlayDb(contexto).readableDatabase
        db.rawQuery("SELECT COUNT(*) FROM ${ProofOfPlayDb.TABELA} WHERE terminado_em IS NULL", null).use {
            it.moveToFirst()
            return it.getInt(0)
        }
    }

    /** Lê um campo privado da Activity — o estado que o teste precisa provar. */
    @Suppress("UNCHECKED_CAST")
    fun <T> campo(atividade: PlayerActivity, nome: String): T {
        val f = PlayerActivity::class.java.getDeclaredField(nome)
        f.isAccessible = true
        return f.get(atividade) as T
    }

    fun <T : android.view.View> vista(atividade: PlayerActivity, id: Int): T = atividade.findViewById(id)

    /** Clica na tecla da grade (instalação ou PIN) que mostra [texto]. */
    fun tecla(atividade: PlayerActivity, grade: Int, texto: String) {
        val teclado = vista<android.widget.GridLayout>(atividade, grade)
        val alvo = (0 until teclado.childCount).map(teclado::getChildAt)
            .first { (it as android.widget.TextView).text.toString() == texto }
        alvo.performClick()
    }

    /** Grava uma config como se tivesse vindo do servidor. */
    fun aplicarConfig(corpo: String) {
        val config = ConfigAparelho(contexto)
        check(config.aplicarConfig(br.com.mostrai.player.config.ConfigRemotaJson.parse(corpo)!!, corpo))
    }

    fun voltar(atividade: PlayerActivity) {
        atividade.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
        atividade.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        idle()
    }

    fun encerrar() {
        br.com.mostrai.player.cache.MedidorDeDisco.padrao()
        br.com.mostrai.player.atualizacao.FontesAtualizacao.padrao()
        br.com.mostrai.player.atualizacao.EstadoAtualizacao.reiniciar()
        servidor.encerrar()
        HostDaApi.base = br.com.mostrai.player.Produto.BASE_URL
    }
}
