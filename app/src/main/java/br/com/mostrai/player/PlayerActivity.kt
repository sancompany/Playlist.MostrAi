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
import br.com.mostrai.player.config.MargensOverscan
import br.com.mostrai.player.estado.DiarioBordo
import br.com.mostrai.player.estado.EstadoPlayer
import br.com.mostrai.player.kiosk.Kiosk
import br.com.mostrai.player.kiosk.Watchdog
import br.com.mostrai.player.network.EstadoRede
import br.com.mostrai.player.network.HeartbeatJson
import br.com.mostrai.player.network.HelloJson
import br.com.mostrai.player.network.MostraiApi
import br.com.mostrai.player.network.SincronizacaoV2
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
import br.com.mostrai.player.update.Atualizador
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
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
    private lateinit var diario: DiarioBordo
    private lateinit var atualizador: Atualizador
    private lateinit var sincronizacao: SincronizacaoV2

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

    /** criativoId do item no ar — vai no heartbeat. */
    private var criativoAtualId: String? = null

    private var estadoAtual: EstadoPlayer = EstadoPlayer.IDLE

    /**
     * Guarda o que já está aplicado na tela (R11). Trocar `layoutParams`
     * dispara `requestLayout()` incondicionalmente, mesmo com valores
     * idênticos — sem esta comparação, cada heartbeat forçaria uma passada de
     * layout na hierarquia que contém o `TextureView` do vídeo, a cada 5
     * minutos, para sempre e sem motivo.
     */
    private var margensAplicadas: MargensOverscan? = null
    private var rotacaoAplicada: Int? = null

    /**
     * Impede dois GETs de playlist simultâneos (Parte 17): o poll periódico,
     * a virada de hora e o `playlist.atualizar` do heartbeat podem coincidir,
     * e duas respostas em voo aplicariam reposicionamento uma por cima da
     * outra.
     */
    private val buscandoPlaylist = AtomicBoolean(false)

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
            dispararHeartbeat()
            handler.postDelayed(this, INTERVALO_HEARTBEAT_MS)
        }
    }

    private val flushFilaPeriodico = object : Runnable {
        override fun run() {
            lifecycleScope.launch(Dispatchers.IO) { fila.tentarEnviar() }
            handler.postDelayed(this, INTERVALO_FLUSH_MS)
        }
    }

    /** Reavalia o horário de funcionamento sem depender de rede. */
    private val checarHorario = object : Runnable {
        override fun run() {
            aplicarHorarioOperacional()
            handler.postDelayed(this, INTERVALO_HORARIO_MS)
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
        diario = DiarioBordo(this)
        // Ordem: build embutido (-PconfigDispositivo) primeiro — se já veio
        // configurado assim, nem chega a pedir permissão de armazenamento.
        @Suppress("DEPRECATION")
        config.aplicarConfiguracaoEmbutidaSeNecessaria()
        if (!config.provisionado) pedirPermissaoOuAplicarConfigExterna()

        api = MostraiApi(config)
        repositorio = PlaylistRepositorio(this, api)
        fila = FilaProofOfPlay(this, api)
        cacheMidia = CacheMidia(this)
        atualizador = Atualizador(this, diario)
        sincronizacao = SincronizacaoV2(config, api, diario, atualizador)

        raiz = findViewById(R.id.raiz)
        rotor = findViewById(R.id.rotor)
        playerView = findViewById(R.id.player)
        institucional = findViewById(R.id.institucional)

        aplicarProvisionamentoProvisorio(intent)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        aplicarRotacaoEMargem()

        diario.registrar(DiarioBordo.Codigo.BOOT, "versão ${BuildConfig.VERSION_NAME}")
        Kiosk.ativarLockTaskSePossivel(this)
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
     * Caminho de depuração em bancada; o caminho de campo é o
     * `mostrai-config.json` no pendrive. Sobrescreve sempre, de propósito —
     * é o que permite corrigir uma tela em bancada sem reinstalar nada.
     */
    private fun aplicarProvisionamentoProvisorio(origem: Intent?) {
        val extras = origem?.extras ?: return
        extras.getString(EXTRA_DISPOSITIVO)?.let { config.dispositivoId = it }
        extras.getString(EXTRA_CHAVE)?.let { config.chaveAparelho = it }
        extras.getString(EXTRA_TOKEN)?.let { config.tokenProvisionamento = it }
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
        Watchdog.registrarSinalDeVida(this)
        Watchdog.agendar(this)
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
        aplicarHorarioOperacional()

        atualizarPlaylist(forcarReposicionamento = true)
        // R10: o primeiro sinal sai agora, não daqui a 5 minutos — até aqui
        // toda tela ficava invisível para o admin durante o boot inteiro.
        dispararHeartbeat(primeiroDoBoot = true)
        handler.postDelayed(buscarPeriodicamente, INTERVALO_POLL_MS)
        handler.postDelayed(heartbeatPeriodico, INTERVALO_HEARTBEAT_MS)
        handler.postDelayed(flushFilaPeriodico, INTERVALO_FLUSH_MS)
        handler.postDelayed(checarHorario, INTERVALO_HORARIO_MS)
        agendarViradaDeHora()

        lifecycleScope.launch(Dispatchers.IO) { fila.tentarEnviar() }
        conectividade().registerDefaultNetworkCallback(callbackConectividade)
    }

    override fun onResume() {
        super.onResume()
        esconderInterfaceDoSistema()
        Watchdog.registrarSinalDeVida(this)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        runCatching { conectividade().unregisterNetworkCallback(callbackConectividade) }
        // R5: a exibição em andamento morre aqui. Sem cancelar o registro, a
        // linha fica órfã na fila — nunca ganha terminadoEm, nunca é enviada,
        // e ocupa o teto por 7 dias.
        cancelarExibicaoEmAndamento()
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
        if (!buscandoPlaylist.compareAndSet(false, true)) return
        lifecycleScope.launch {
            try {
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
            } finally {
                buscandoPlaylist.set(false)
            }
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
        cancelarExibicaoEmAndamento()
        tocarItemAtual()
    }

    /**
     * Encerra o registro de uma exibição que não vai terminar (R5). Toda
     * saída que abandona [execucaoAtualId] passa por aqui — reposicionamento,
     * parada da Activity, fora do horário.
     */
    private fun cancelarExibicaoEmAndamento() {
        val id = execucaoAtualId ?: return
        execucaoAtualId = null
        lifecycleScope.launch(Dispatchers.IO) { fila.registrarFalha(id) }
    }

    private fun atualizarEstadoRede(resultado: PlaylistRepositorio.Resultado) {
        EstadoRede.contratoNovo = !resultado.playlist.modoDegradado
        EstadoRede.ultimaOrigem = resultado.origem.name
        EstadoRede.ultimoErroAparelho = resultado.erroAparelho
        EstadoRede.ultimaFalhaTransitoria = resultado.falhaTransitoria
        ultimaOrigemFetch = resultado.origem

        when {
            resultado.origem == PlaylistRepositorio.Origem.SERVIDOR -> {
                config.ultimaPlaylistOkEm = OffsetDateTime.now().toString()
                diario.limparErros()
            }
            resultado.erroAparelho != null -> diario.registrar(
                DiarioBordo.Codigo.AUTH_FALHOU,
                "playlist recusada (HTTP ${resultado.erroAparelho})",
            )
            resultado.origem == PlaylistRepositorio.Origem.INSTITUCIONAL -> diario.registrar(
                DiarioBordo.Codigo.PLAYLIST_FALHOU,
                resultado.falhaTransitoria ?: "sem playlist utilizável",
            )
        }
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

    // ---------------------------------------------------------------- horário

    /**
     * Fora do horário do ponto a tela para de vender: nenhum item comercial
     * toca e nenhum proof-of-play nasce. O regime vive inteiro no aparelho
     * porque a loja continua abrindo e fechando quando a internet cai.
     */
    private fun aplicarHorarioOperacional() {
        val dentro = config.horarioOperacional().estaDentro(Instant.now())
        if (dentro) {
            if (estadoAtual == EstadoPlayer.OUT_OF_SCHEDULE) {
                estadoAtual = EstadoPlayer.IDLE
                atualizarPlaylist(forcarReposicionamento = true)
            }
            return
        }

        if (estadoAtual == EstadoPlayer.OUT_OF_SCHEDULE) return

        estadoAtual = EstadoPlayer.OUT_OF_SCHEDULE
        diario.registrar(DiarioBordo.Codigo.FORA_DO_HORARIO)
        handler.removeCallbacks(avancarPorTempo)
        player?.stop()
        cancelarExibicaoEmAndamento()
        criativoAtualId = null
        mostrarInstitucionalSimples(EstadoInstitucional.PADRAO)
    }

    private fun foraDoHorario(): Boolean = estadoAtual == EstadoPlayer.OUT_OF_SCHEDULE

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
                    estadoAtual = EstadoPlayer.PLAYBACK_ERROR
                    diario.registrar(DiarioBordo.Codigo.PLAYBACK_FALHOU, error.errorCodeName)
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
        if (foraDoHorario()) return

        val minhaGeracao = ++geracaoReproducao
        val item = playlist.itens.getOrNull(indice) ?: run {
            indice = 0
            playlist.itens.firstOrNull()
        } ?: return

        // Só a url decide, não a flag institucional: um item institucional
        // com url (vídeo de fundo servido pelo backend) toca normalmente.
        // Sem url cai na tela institucional local, institucional ou não
        // (proteção contra dado incompleto).
        if (item.url.isNullOrBlank()) {
            execucaoAtualId = null
            criativoAtualId = null
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
            // do cache local na mesma ida à thread de fundo (bloco 4 do MVP).
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

            // Hash divergente é mídia comprovadamente errada: tocar a URL
            // remota seria servir exatamente o arquivo que acabou de ser
            // rejeitado. Pula o item (R3).
            if (arquivoLocal == null && !cacheMidia.deveTocarDaUrlRemota()) {
                if (id != null) lifecycleScope.launch(Dispatchers.IO) { fila.registrarFalha(id) }
                estadoAtual = EstadoPlayer.DOWNLOAD_ERROR
                diario.registrar(DiarioBordo.Codigo.MIDIA_HASH_DIVERGENTE, item.criativoId)
                avancar()
                return@launch
            }

            execucaoAtualId = id
            criativoAtualId = item.criativoId
            estadoAtual = EstadoPlayer.PLAYING

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
        mostrarInstitucionalSimples(estadoInstitucional())
        if (estadoAtual != EstadoPlayer.PLAYBACK_ERROR && estadoAtual != EstadoPlayer.DOWNLOAD_ERROR) {
            estadoAtual = if (config.provisionado) EstadoPlayer.IDLE else EstadoPlayer.NOT_PROVISIONED
        }

        val duracao = if (item.duracaoSegundos > 0) item.duracaoSegundos else 10
        handler.postDelayed(avancarPorTempo, duracao * 1000L)
    }

    private fun mostrarInstitucionalSimples(estado: EstadoInstitucional) {
        playerView.visibility = View.GONE
        player?.stop()

        institucional.estado = estado
        // Legenda só é desenhada no estado PADRAO — os outros três têm texto
        // embutido na própria arte (ver TelaInstitucional).
        if (estado == EstadoInstitucional.PADRAO) {
            institucional.legenda = getString(R.string.institucional_sem_programacao)
        }
        institucional.visibility = View.VISIBLE
    }

    /**
     * Três estados são do aparelho, nunca do backend: sem provisionamento
     * (config local incompleta), erro de carregamento (nem servidor nem
     * cache) ou carregando (tratado à parte, em [iniciarCicloNormal]).
     * Qualquer outra coisa é o item institucional que o próprio backend
     * manda quando não há programação pra aquela hora — isso é conteúdo da
     * playlist, não decisão local.
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
        // Janela segura: nenhuma exibição paga no ar entre um item e o
        // próximo. É aqui, e só aqui, que a confirmação de instalação pode
        // aparecer sem cortar o anúncio de ninguém.
        if (tentarInstalarAtualizacao()) return
        avancar()
    }

    private fun avancar() {
        if (foraDoHorario()) return
        if (playlist.itens.isEmpty()) return
        indice = (indice + 1) % playlist.itens.size
        tocarItemAtual()
    }

    // --------------------------------------------------------------- heartbeat

    private fun dispararHeartbeat(primeiroDoBoot: Boolean = false) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (sincronizacao.provisionarSeNecessario()) {
                withContext(Dispatchers.Main) { atualizarPlaylist(forcarReposicionamento = true) }
            }
            if (primeiroDoBoot) {
                sincronizacao.helloSeNecessario(HelloJson.coletar(this@PlayerActivity))
            }

            val resumo = fila.resumo()
            if (resumo.aguardandoEnvio >= FilaProofOfPlay.LIMIAR_ALERTA) {
                diario.registrar(DiarioBordo.Codigo.FILA_LIMIAR, "${resumo.aguardandoEnvio} eventos na fila")
            }
            val erro = diario.ultimoErro()

            val efeitos = sincronizacao.heartbeat(
                HeartbeatJson.Corpo(
                    estado = estadoAtual,
                    configVersionAplicada = config.configVersionAplicada,
                    criativoId = criativoAtualId,
                    ultimaPlaylistOkEm = config.ultimaPlaylistOkEm,
                    filaPendentes = resumo.aguardandoEnvio,
                    filaMaisAntigoEm = resumo.maisAntigoMs?.let {
                        OffsetDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()).toString()
                    },
                    erroCodigo = erro?.codigo,
                    erroEm = erro?.emIso,
                    erroMensagem = erro?.mensagem,
                    desvioRelogioMs = desvioRelogioMs(),
                    updateEstado = atualizador.estado.name,
                )
            )

            withContext(Dispatchers.Main) { aplicarEfeitos(efeitos) }
        }
    }

    /**
     * Diferença entre o relógio de parede da TV e o do servidor, quando há
     * âncora válida. É de graça: [RelogioJanela] já mantém essa referência
     * para a reentrada por posição temporal.
     */
    private fun desvioRelogioMs(): Long? {
        val relogio = relogioJanela?.takeIf { it.valida() } ?: return null
        return System.currentTimeMillis() - relogio.agoraDoServidorMs()
    }

    private fun aplicarEfeitos(efeitos: SincronizacaoV2.Efeitos) {
        if (efeitos.autenticacaoFalhou) estadoAtual = EstadoPlayer.AUTH_ERROR
        if (efeitos.margens != null || efeitos.rotacao != null) {
            efeitos.margens?.let { config.margensOverscan = it }
            efeitos.rotacao?.let { config.rotacaoTela = it }
            aplicarRotacaoEMargem()
        }
        aplicarHorarioOperacional()
        if (efeitos.atualizarPlaylist) atualizarPlaylist(forcarReposicionamento = false)
    }

    /**
     * Pede a instalação se houver uma pronta. Devolve true quando o diálogo
     * foi aberto — aí o ciclo de exibição para por aqui; se o operador
     * cancelar, [Atualizador] agenda a próxima tentativa e o player volta ao
     * normal no próximo item.
     */
    private fun tentarInstalarAtualizacao(): Boolean {
        if (!atualizador.podePedirInstalacao()) return false
        estadoAtual = EstadoPlayer.UPDATE_PENDING
        return atualizador.pedirInstalacao()
    }

    // ------------------------------------------------------------------- tela

    /**
     * Margem de overscan e rotação de tela só fazem sentido depois que
     * `raiz` foi medida — por isso o `post`. Lógica compartilhada com
     * `PainelActivity` em [RotacaoTela] (mesmo padrão raiz/rotor nas duas).
     *
     * R11: só reaplica se algo mudou de verdade. `setLayoutParams` chama
     * `requestLayout()` mesmo com valores idênticos, e essa passada de
     * layout atravessa o `TextureView` que está exibindo o vídeo.
     */
    private fun aplicarRotacaoEMargem() {
        val margens = config.margensOverscan
        val rotacao = config.rotacaoTela
        if (margens == margensAplicadas && rotacao == rotacaoAplicada) return

        margensAplicadas = margens
        rotacaoAplicada = rotacao
        raiz.post { RotacaoTela.aplicar(raiz, rotor, margens, rotacao) }
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
        const val EXTRA_TOKEN = "tokenProvisionamento"
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
        const val INTERVALO_HORARIO_MS = 60_000L
    }
}
