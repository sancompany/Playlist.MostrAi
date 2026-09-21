package br.com.mostrai.player

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import br.com.mostrai.player.cache.CacheMidia
import br.com.mostrai.player.config.ConfigAparelho
import br.com.mostrai.player.network.EstadoRede
import br.com.mostrai.player.network.MostraiApi
import br.com.mostrai.player.playlist.ItemPlaylist
import br.com.mostrai.player.playlist.Playlist
import br.com.mostrai.player.playlist.PlaylistRepositorio
import br.com.mostrai.player.playlist.PosicaoNaPlaylist
import br.com.mostrai.player.playlist.RelogioJanela
import br.com.mostrai.player.playlist.ReposicionamentoPlaylist
import br.com.mostrai.player.proof.FilaProofOfPlay
import br.com.mostrai.player.ui.GestoPainel
import br.com.mostrai.player.ui.PainelActivity
import br.com.mostrai.player.ui.TelaInstitucional
import java.time.OffsetDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tela única do player: vídeo em tela cheia, mudo, em laço, buscando a
 * playlist do servidor.
 *
 * O ciclo avança item a item em vez de usar a fila do ExoPlayer, porque a
 * decisão fechada do projeto é que só a conclusão real conta — cada exibição
 * precisa terminar num STATE_ENDED próprio, observável, que é exatamente o
 * gancho onde o comprovante de exibição entra.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var config: ConfigAparelho
    private lateinit var api: MostraiApi
    private lateinit var repositorio: PlaylistRepositorio
    private lateinit var fila: FilaProofOfPlay
    private lateinit var cacheMidia: CacheMidia

    private lateinit var playerView: PlayerView
    private lateinit var institucional: TelaInstitucional
    private lateinit var raiz: View

    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    private var playlist: Playlist = Playlist.somenteInstitucional()
    private var janelaIdAtual: String? = null
    private var relogioJanela: RelogioJanela? = null
    private var indice = 0

    /** execucaoId da exibição em andamento, ou null se o item não conta. */
    private var execucaoAtualId: String? = null

    /**
     * Incrementada a cada chamada de [tocarItemAtual]. `mostrarVideo` guarda a
     * geração com que foi chamado e confere antes de aplicar o resultado —
     * sem isso, uma corrotina de um item anterior que ainda está resolvendo
     * cache/registrando início pode terminar DEPOIS de um item mais novo já
     * ter assumido a tela, e sobrescrever `execucaoAtualId` e o item do
     * ExoPlayer com o item errado.
     */
    private var geracaoReproducao = 0

    private val gestoPainel = GestoPainel { abrirPainel() }

    private val avancarPorTempo = Runnable { avancar() }

    private val buscarPeriodicamente = object : Runnable {
        override fun run() {
            atualizarPlaylist(forcarReposicionamento = false)
            handler.postDelayed(this, INTERVALO_POLL_MS)
        }
    }

    private val heartbeatPeriodico = object : Runnable {
        override fun run() {
            lifecycleScope.launch(Dispatchers.IO) { api.heartbeat() }
            handler.postDelayed(this, INTERVALO_HEARTBEAT_MS)
        }
    }

    private val flushFilaPeriodico = object : Runnable {
        override fun run() {
            lifecycleScope.launch(Dispatchers.IO) { fila.tentarEnviar() }
            handler.postDelayed(this, INTERVALO_FLUSH_MS)
        }
    }

    /** Dispara a fila assim que a rede volta — não espera o próximo temporizador. */
    private val callbackConectividade = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            lifecycleScope.launch(Dispatchers.IO) { fila.tentarEnviar() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        config = ConfigAparelho(this)
        api = MostraiApi(config)
        repositorio = PlaylistRepositorio(this, api)
        fila = FilaProofOfPlay(this, api)
        cacheMidia = CacheMidia(this)

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

        atualizarPlaylist(forcarReposicionamento = true)
        handler.postDelayed(buscarPeriodicamente, INTERVALO_POLL_MS)
        handler.postDelayed(heartbeatPeriodico, INTERVALO_HEARTBEAT_MS)
        handler.postDelayed(flushFilaPeriodico, INTERVALO_FLUSH_MS)
        agendarViradaDeHora()

        lifecycleScope.launch(Dispatchers.IO) { fila.tentarEnviar() }
        conectividade().registerDefaultNetworkCallback(callbackConectividade)
    }

    override fun onResume() {
        super.onResume()
        esconderInterfaceDoSistema()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        runCatching { conectividade().unregisterNetworkCallback(callbackConectividade) }
        liberarPlayer()
    }

    private fun conectividade() =
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // --------------------------------------------------------------- playlist

    /**
     * Busca a playlist no servidor (ou cai para cache/institucional) e
     * decide o que fazer com o índice — a decisão em si mora em
     * [ReposicionamentoPlaylist], testável sem Android; aqui só se aplica o
     * resultado. Ver a doc daquele objeto para os três casos (início
     * frio/janela nova, modo degradado, reancoragem por `itemProgramacaoId`).
     */
    private fun atualizarPlaylist(forcarReposicionamento: Boolean) {
        lifecycleScope.launch {
            val resultado = withContext(Dispatchers.IO) { repositorio.buscar() }

            val playlistAnterior = playlist
            val indiceAnterior = indice
            val trocouDeJanela = resultado.playlist.janelaId != janelaIdAtual

            playlist = resultado.playlist
            relogioJanela = resultado.relogio
            janelaIdAtual = resultado.playlist.janelaId
            atualizarEstadoRede(resultado)
            preAquecerCache(playlist)

            val decisao = ReposicionamentoPlaylist.decidir(
                playlistAnterior = playlistAnterior,
                indiceAnterior = indiceAnterior,
                playlistNova = playlist,
                forcarReposicionamento = forcarReposicionamento,
                trocouDeJanela = trocouDeJanela,
                indiceInicialPorTempo = ::calcularIndiceInicial,
            )
            indice = decisao.indice
            if (decisao.reiniciarAgora) reiniciarItemAgora()
        }
    }

    /** Reentrada por posição temporal (item 7.1) — nunca por índice salvo. */
    private fun calcularIndiceInicial(): Int {
        val relogio = relogioJanela?.takeIf { it.valida() } ?: return 0
        val inicioIso = playlist.janelaInicio ?: return 0
        val inicioMs = runCatching { OffsetDateTime.parse(inicioIso).toInstant().toEpochMilli() }
            .getOrNull() ?: return 0
        val decorridoMs = relogio.agoraDoServidorMs() - inicioMs
        return PosicaoNaPlaylist.calcular(playlist.itens, decorridoMs)
    }

    private fun reiniciarItemAgora() {
        handler.removeCallbacks(avancarPorTempo)
        player?.stop()
        execucaoAtualId = null
        tocarItemAtual()
    }

    private fun atualizarEstadoRede(resultado: PlaylistRepositorio.Resultado) {
        EstadoRede.contratoNovo = !resultado.playlist.modoDegradado
        EstadoRede.ultimaOrigem = resultado.origem.name
        EstadoRede.ultimoErroAparelho = resultado.erroAparelho
        EstadoRede.ultimaFalhaTransitoria = resultado.falhaTransitoria
    }

    /**
     * Acorda na virada da hora com um atraso derivado da chave do aparelho
     * (nunca do relógio, sempre o mesmo atraso) para as telas da rede não
     * baterem juntas no servidor. Usa o relógio de parede só para decidir
     * QUANDO acordar — nenhuma decisão de crédito depende disso; se o
     * relógio da TV estiver errado, o próximo poll periódico corrige.
     */
    private fun agendarViradaDeHora() {
        val agora = java.util.Calendar.getInstance()
        val proximaHora = (agora.clone() as java.util.Calendar).apply {
            add(java.util.Calendar.HOUR_OF_DAY, 1)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val atrasoJitterMs = config.atrasoViradaSegundos() * 1000L
        val atrasoMs = (proximaHora.timeInMillis - agora.timeInMillis) + atrasoJitterMs

        handler.postDelayed({
            atualizarPlaylist(forcarReposicionamento = false)
            agendarViradaDeHora()
        }, atrasoMs.coerceAtLeast(1_000L))
    }

    /**
     * Baixa de antemão os itens da playlist que ainda não estão em cache
     * (bloco 4 do MVP), para que a primeira exibição de cada um não fique
     * esperando o download. Dispara em segundo plano, sem bloquear nem
     * atrasar a reprodução em andamento.
     */
    private fun preAquecerCache(playlist: Playlist) {
        lifecycleScope.launch(Dispatchers.IO) { cacheMidia.preAquecer(playlist.itens) }
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
                    val id = execucaoAtualId
                    execucaoAtualId = null
                    // Falha de reprodução NÃO é exibição: a linha nunca teve
                    // terminadoEm, não é uma alegação de exibição completa.
                    if (id != null) lifecycleScope.launch(Dispatchers.IO) { fila.registrarFalha(id) }
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
        val minhaGeracao = ++geracaoReproducao
        val item = playlist.itens.getOrNull(indice) ?: run {
            indice = 0
            playlist.itens.firstOrNull()
        } ?: return

        if (item.institucional || item.url.isNullOrBlank()) {
            execucaoAtualId = null
            mostrarInstitucional(item)
        } else {
            mostrarVideo(item, minhaGeracao)
        }
    }

    private fun mostrarVideo(item: ItemPlaylist, minhaGeracao: Int) {
        val playlistDoItem = playlist
        lifecycleScope.launch {
            // A linha da fila nasce ANTES do play() (decisão 3, seção 4) — só
            // toca depois que o execucaoId está persistido. Resolve o arquivo
            // do cache local na mesma ida à thread de fundo (bloco 4 do MVP);
            // se não conseguir (sem cache e download falhou), cai para tocar
            // direto da URL remota — nunca trava a exibição por causa do cache.
            val (id, arquivoLocal) = withContext(Dispatchers.IO) {
                fila.registrarInicio(item, playlistDoItem) to cacheMidia.resolver(item)
            }

            if (minhaGeracao != geracaoReproducao) {
                // Um item mais novo já assumiu a tela enquanto isto resolvia
                // (troca de janela, painel reposicionando etc.) — nunca toca
                // por cima do que já está rodando. A linha nunca teve
                // terminadoEm, então descartá-la é correto, não é perda.
                if (id != null) lifecycleScope.launch(Dispatchers.IO) { fila.registrarFalha(id) }
                return@launch
            }

            execucaoAtualId = id

            institucional.visibility = View.GONE
            playerView.visibility = View.VISIBLE

            val exo = player ?: return@launch
            val uri = if (arquivoLocal != null) Uri.fromFile(arquivoLocal) else Uri.parse(item.url!!)
            exo.setMediaItem(MediaItem.fromUri(uri))
            exo.prepare()
            exo.playWhenReady = true
        }
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
        val id = execucaoAtualId
        execucaoAtualId = null
        if (id != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                fila.registrarFim(id)
                fila.tentarEnviar()
            }
        }
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

        const val INTERVALO_POLL_MS = 15 * 60_000L
        const val INTERVALO_HEARTBEAT_MS = 5 * 60_000L
        const val INTERVALO_FLUSH_MS = 60_000L
    }
}
