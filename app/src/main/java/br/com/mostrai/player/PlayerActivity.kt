package br.com.mostrai.player

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import br.com.mostrai.player.config.ConfigAparelho
import br.com.mostrai.player.playlist.ItemPlaylist
import br.com.mostrai.player.playlist.Playlist
import br.com.mostrai.player.ui.GestoPainel
import br.com.mostrai.player.ui.PainelActivity
import br.com.mostrai.player.ui.TelaInstitucional
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tela única do player: vídeo em tela cheia, mudo, em laço.
 *
 * O ciclo avança item a item em vez de usar a fila do ExoPlayer, porque a
 * decisão fechada do projeto é que só a conclusão real conta — cada exibição
 * precisa terminar num STATE_ENDED próprio, observável.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var config: ConfigAparelho
    private lateinit var playerView: PlayerView
    private lateinit var institucional: TelaInstitucional
    private lateinit var raiz: View

    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    private var playlist: Playlist = Playlist.somenteInstitucional()
    private var indice = 0

    private val gestoPainel = GestoPainel { abrirPainel() }

    private val avancarPorTempo = Runnable { avancar() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        config = ConfigAparelho(this)
        raiz = findViewById(R.id.raiz)
        playerView = findViewById(R.id.player)
        institucional = findViewById(R.id.institucional)

        aplicarProvisionamentoProvisorio(intent)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        aplicarMargemOverscan()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        aplicarProvisionamentoProvisorio(intent)
        aplicarMargemOverscan()
    }

    /**
     * Provisionamento de laboratório, por extras do Intent (adb).
     *
     * PROVISÓRIO: como a chave do aparelho entra na TV no primeiro boot ainda é
     * uma decisão em aberto do projeto. Serve para testar em bancada enquanto
     * isso não fecha; não é o caminho de campo.
     */
    private fun aplicarProvisionamentoProvisorio(origem: Intent?) {
        val extras = origem?.extras ?: return
        extras.getString(EXTRA_DISPOSITIVO)?.let { config.dispositivoId = it }
        extras.getString(EXTRA_CHAVE)?.let { config.chaveAparelho = it }
        extras.getString(EXTRA_BASE_URL)?.let { config.baseUrl = it }
        extras.getString(EXTRA_PIN)?.let { config.pinPainel = it }
        if (extras.containsKey(EXTRA_MARGEM)) {
            config.margemVmin = extras.getFloat(EXTRA_MARGEM, config.margemVmin)
        }
    }

    override fun onStart() {
        super.onStart()
        criarPlayer()
        tocarItemAtual()
    }

    override fun onResume() {
        super.onResume()
        esconderInterfaceDoSistema()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(avancarPorTempo)
        liberarPlayer()
    }

    // ---------------------------------------------------------------- player

    private fun criarPlayer() {
        if (player != null) return
        player = ExoPlayer.Builder(this).build().also { exo ->
            exo.volume = 0f
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) concluirExibicao()
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "falha ao reproduzir item $indice", error)
                    // Falha de reprodução NÃO é exibição: nada a comprovar, só segue.
                    avancar()
                }
            })
            playerView.player = exo
        }
    }

    private fun liberarPlayer() {
        playerView.player = null
        player?.release()
        player = null
    }

    private fun tocarItemAtual() {
        handler.removeCallbacks(avancarPorTempo)
        val item = playlist.itens.getOrNull(indice) ?: run {
            indice = 0
            playlist.itens.firstOrNull()
        } ?: return

        if (item.institucional || item.url.isNullOrBlank()) {
            mostrarInstitucional(item)
        } else {
            mostrarVideo(item)
        }
    }

    private fun mostrarVideo(item: ItemPlaylist) {
        institucional.visibility = View.GONE
        playerView.visibility = View.VISIBLE

        val exo = player ?: return
        exo.setMediaItem(MediaItem.fromUri(item.url!!))
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun mostrarInstitucional(item: ItemPlaylist) {
        playerView.visibility = View.GONE
        player?.stop()
        institucional.legenda = legendaInstitucional()
        institucional.visibility = View.VISIBLE

        val duracao = if (item.duracaoSegundos > 0) item.duracaoSegundos else 10
        handler.postDelayed(avancarPorTempo, duracao * 1000L)
    }

    private fun legendaInstitucional(): String = getString(
        if (config.provisionado) R.string.institucional_sem_programacao
        else R.string.institucional_sem_provisionamento
    )

    /** Chamado apenas no STATE_ENDED: é aqui que a exibição vira comprovante. */
    private fun concluirExibicao() {
        // TODO(fatia 2): registrar terminadoEm na fila durável de proof-of-play.
        avancar()
    }

    private fun avancar() {
        if (playlist.itens.isEmpty()) return
        indice = (indice + 1) % playlist.itens.size
        tocarItemAtual()
    }

    // ------------------------------------------------------------------- tela

    /**
     * Compensa a moldura física de TV que corta a borda da imagem (overscan).
     * A margem é dada em vmin, como no player web.
     */
    private fun aplicarMargemOverscan() {
        val vmin = config.margemVmin
        if (vmin <= 0f) return
        raiz.post {
            val base = min(raiz.width, raiz.height)
            val px = (base * vmin / 100f).roundToInt()
            raiz.setPadding(px, px, px, px)
        }
    }

    @Suppress("DEPRECATION")
    private fun esconderInterfaceDoSistema() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) esconderInterfaceDoSistema()
    }

    // ------------------------------------------------------------------ painel

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (gestoPainel.aoTeclar(keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun abrirPainel() {
        startActivity(Intent(this, PainelActivity::class.java))
    }

    private companion object {
        const val TAG = "MostraiPlayer"

        const val EXTRA_DISPOSITIVO = "dispositivoId"
        const val EXTRA_CHAVE = "chaveAparelho"
        const val EXTRA_BASE_URL = "baseUrl"
        const val EXTRA_PIN = "pin"
        const val EXTRA_MARGEM = "margemVmin"
    }
}
