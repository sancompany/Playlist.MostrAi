package br.com.mostrai.player

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import br.com.mostrai.player.cache.CacheMidia
import br.com.mostrai.player.config.ConfigAparelho
import br.com.mostrai.player.config.ConfigExterna
import br.com.mostrai.player.network.EstadoRede
import br.com.mostrai.player.network.MostraiApi
import br.com.mostrai.player.playlist.ItemPlaylist
import br.com.mostrai.player.playlist.Playlist
import br.com.mostrai.player.playlist.PlaylistRepositorio
import br.com.mostrai.player.playlist.PosicaoNaPlaylist
import br.com.mostrai.player.playlist.RelogioJanela
import br.com.mostrai.player.playlist.ReposicionamentoPlaylist
import br.com.mostrai.player.proof.FilaProofOfPlay
import br.com.mostrai.player.ui.EstadoInstitucional
import br.com.mostrai.player.ui.GestoPainel
import br.com.mostrai.player.ui.PainelActivity
import br.com.mostrai.player.ui.RotacaoTela
import br.com.mostrai.player.ui.TelaInstitucional
import java.time.OffsetDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /**
     * Carrega o conteúdo de verdade (player + institucional). Tamanho e
     * rotação calculados em [aplicarRotacaoEMargem] a partir de
     * [ConfigAparelho.rotacaoTela] — compensa um painel montado fisicamente
     * de lado, comum em sinalização digital em espaço estreito.
     */
    private lateinit var rotor: FrameLayout

    private var player: ExoPlayer? = null

    /** Player à parte do vídeo de abertura — ver [tocarIntroducao]. */
    private var introPlayer: ExoPlayer? = null

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

    /** Origem da última busca de playlist — decide se a institucional mostra erro. */
    private var ultimaOrigemFetch: PlaylistRepositorio.Origem = PlaylistRepositorio.Origem.INSTITUCIONAL

    /** Cobre só a primeira vez, depois do vídeo de abertura — retomar do painel não mostra de novo. */
    private var primeiraCargaFeita = false

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

    /**
     * Só pede a permissão de armazenamento quando o aparelho ainda não está
     * provisionado e existe algo a ganhar em ler o pendrive (README,
     * "Configurar por um arquivo no pendrive"). Negada, ou sem ninguém para
     * conceder no primeiro boot, o app não trava: segue sem provisionar, cai
     * na tela institucional, e o painel de PIN mostra o estado.
     */
    private val lancadorPermissaoArmazenamento = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concedida ->
        // Assíncrono: quando isto resolve, onStart() já rodou e já tentou
        // buscar a playlist sem config nenhuma. Sem o retomarSeProvisionou,
        // o app só buscaria de novo no próximo poll periódico — até 15 min
        // depois de já estar configurado.
        if (concedida) aplicarConfigExternaSeNecessaria(retomarSeProvisionou = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        config = ConfigAparelho(this)
        // Ordem: build embutido (-PconfigDispositivo) primeiro — se já veio
        // configurado assim, nem chega a pedir permissão de armazenamento.
        config.aplicarConfiguracaoEmbutidaSeNecessaria()
        if (!config.provisionado) pedirPermissaoOuAplicarConfigExterna()

        api = MostraiApi(config)
        repositorio = PlaylistRepositorio(this, api)
        fila = FilaProofOfPlay(this, api)
        cacheMidia = CacheMidia(this)

        raiz = findViewById(R.id.raiz)
        rotor = findViewById(R.id.rotor)
        playerView = findViewById(R.id.player)
        institucional = findViewById(R.id.institucional)

        aplicarProvisionamentoProvisorio(intent)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        aplicarRotacaoEMargem()
    }

    private fun pedirPermissaoOuAplicarConfigExterna() {
        val jaConcedida = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED

        if (jaConcedida) {
            // Síncrono, dentro de onCreate: o onStart() que vem a seguir já
            // busca a playlist com a config em dia — não precisa retomar.
            aplicarConfigExternaSeNecessaria(retomarSeProvisionou = false)
        } else {
            lancadorPermissaoArmazenamento.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun aplicarConfigExternaSeNecessaria(retomarSeProvisionou: Boolean) {
        ConfigExterna.procurarEAplicar(this, config)
        if (retomarSeProvisionou && config.provisionado) {
            atualizarPlaylist(forcarReposicionamento = true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        aplicarProvisionamentoProvisorio(intent)
        aplicarRotacaoEMargem()
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
        if (extras.containsKey(EXTRA_MARGEM_TOPO)) {
            config.margemVminTopo = extras.getFloat(EXTRA_MARGEM_TOPO, config.margemVminTopo)
        }
        if (extras.containsKey(EXTRA_MARGEM_BASE)) {
            config.margemVminBase = extras.getFloat(EXTRA_MARGEM_BASE, config.margemVminBase)
        }
        if (extras.containsKey(EXTRA_MARGEM_ESQUERDA)) {
            config.margemVminEsquerda = extras.getFloat(EXTRA_MARGEM_ESQUERDA, config.margemVminEsquerda)
        }
        if (extras.containsKey(EXTRA_MARGEM_DIREITA)) {
            config.margemVminDireita = extras.getFloat(EXTRA_MARGEM_DIREITA, config.margemVminDireita)
        }
        if (extras.containsKey(EXTRA_ROTACAO)) {
            config.rotacaoTela = extras.getInt(EXTRA_ROTACAO, config.rotacaoTela)
        }
        // aplicarRotacaoEMargem() já é chamado logo depois, por quem chamou
        // este método (onCreate/onNewIntent) — cobre margem e rotação juntos.
    }

    override fun onStart() {
        super.onStart()
        if (introJaTocou) iniciarCicloNormal() else tocarIntroducao()
    }

    /**
     * Vídeo de abertura da marca — só no processo recém-iniciado (o app é o
     * único que roda na tela, então isto é o "boot" visível). Num player
     * próprio, separado de [player]: o listener normal (`concluirExibicao`,
     * fila de proof-of-play) não pode reagir ao `STATE_ENDED` do vídeo de
     * abertura, que não é exibição de anunciante nenhuma.
     */
    private fun tocarIntroducao() {
        introJaTocou = true
        institucional.visibility = View.GONE
        playerView.visibility = View.VISIBLE

        val intro = ExoPlayer.Builder(this).build().also { introPlayer = it }
        intro.volume = 0f
        intro.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) encerrarIntroducao()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "falha ao tocar o vídeo de abertura", error)
                encerrarIntroducao()
            }
        })
        playerView.player = intro
        intro.setMediaItem(MediaItem.fromUri(Uri.parse("android.resource://$packageName/${R.raw.video_abertura}")))
        intro.prepare()
        intro.playWhenReady = true
    }

    /**
     * Chamado tanto pelo fim natural do vídeo (`STATE_ENDED`/erro) quanto
     * por [onStop] se o aparelho for parado no meio da introdução — nos dois
     * casos, libera o player à parte e segue pro ciclo normal (que recria
     * tudo do zero via [criarPlayer]).
     */
    private fun encerrarIntroducao() {
        if (playerView.player === introPlayer) playerView.player = null
        introPlayer?.release()
        introPlayer = null
        iniciarCicloNormal()
    }

    private fun iniciarCicloNormal() {
        if (!primeiraCargaFeita) {
            primeiraCargaFeita = true
            // Só faz sentido "carregando" se há o que carregar — sem
            // provisionamento a institucional já vai direto pro estado
            // certo (NAO_PROVISIONADO) assim que a primeira busca falhar.
            if (config.provisionado) {
                institucional.estado = EstadoInstitucional.CARREGANDO
                institucional.visibility = View.VISIBLE
                playerView.visibility = View.GONE
            }
        }

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
        // Só libera — nunca chama encerrarIntroducao()/iniciarCicloNormal()
        // aqui, que iniciaria um ciclo novo de trabalho (rede, handlers)
        // bem no momento em que a Activity está parando.
        if (playerView.player === introPlayer) playerView.player = null
        introPlayer?.release()
        introPlayer = null
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
        ultimaOrigemFetch = resultado.origem
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

        // Só a url decide, não a flag institucional: um item institucional
        // com url (vídeo de fundo servido pelo backend, ainda sem contrato
        // — PARA-O-BACKEND.md) toca normalmente, sem esperar outra versão
        // deste app. Sem url — o caso de hoje — cai na tela institucional
        // local, institucional ou não (proteção contra dado incompleto).
        if (item.url.isNullOrBlank()) {
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

        val estado = estadoInstitucional()
        institucional.estado = estado
        // Legenda só é desenhada no estado PADRAO — os outros três têm texto
        // embutido na própria arte (ver TelaInstitucional).
        if (estado == EstadoInstitucional.PADRAO) {
            institucional.legenda = getString(R.string.institucional_sem_programacao)
        }
        institucional.visibility = View.VISIBLE

        val duracao = if (item.duracaoSegundos > 0) item.duracaoSegundos else 10
        handler.postDelayed(avancarPorTempo, duracao * 1000L)
    }

    /**
     * Três estados são do aparelho, nunca do backend: sem provisionamento
     * (config local incompleta), erro de carregamento (nem servidor nem
     * cache — [PlaylistRepositorio.Origem.INSTITUCIONAL]) ou carregando
     * (tratado à parte, em [iniciarCicloNormal]). Qualquer outra coisa é o
     * item institucional que o próprio backend manda quando não há
     * programação pra aquela hora — isso é conteúdo da playlist, não
     * decisão local (PADRAO, com a legenda desenhada em runtime).
     */
    private fun estadoInstitucional(): EstadoInstitucional = when {
        !config.provisionado -> EstadoInstitucional.NAO_PROVISIONADO
        ultimaOrigemFetch == PlaylistRepositorio.Origem.INSTITUCIONAL -> EstadoInstitucional.ERRO_CARREGAR
        else -> EstadoInstitucional.PADRAO
    }

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
     * Margem de overscan e rotação de tela só fazem sentido depois que
     * `raiz` foi medida — por isso o `post`. Lógica compartilhada com
     * `PainelActivity` em [RotacaoTela] (mesmo padrão raiz/rotor nas duas).
     */
    private fun aplicarRotacaoEMargem() {
        raiz.post { RotacaoTela.aplicar(raiz, rotor, config.margensOverscan, config.rotacaoTela) }
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

        /**
         * Sobrevive a recriação da Activity (não a reinício do processo) —
         * o vídeo de abertura só faz sentido no boot de verdade, nunca ao
         * voltar do painel de manutenção.
         */
        var introJaTocou = false

        const val EXTRA_DISPOSITIVO = "dispositivoId"
        const val EXTRA_CHAVE = "chaveAparelho"
        const val EXTRA_BASE_URL = "baseUrl"
        const val EXTRA_PIN = "pin"
        const val EXTRA_MARGEM_TOPO = "margemVminTopo"
        const val EXTRA_MARGEM_BASE = "margemVminBase"
        const val EXTRA_MARGEM_ESQUERDA = "margemVminEsquerda"
        const val EXTRA_MARGEM_DIREITA = "margemVminDireita"
        const val EXTRA_ROTACAO = "rotacaoTela"

        const val INTERVALO_POLL_MS = 15 * 60_000L
        const val INTERVALO_HEARTBEAT_MS = 5 * 60_000L
        const val INTERVALO_FLUSH_MS = 60_000L
    }
}
