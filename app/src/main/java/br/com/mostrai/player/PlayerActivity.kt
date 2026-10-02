package br.com.mostrai.player

import android.content.Context
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
import br.com.mostrai.player.config.MargensOverscan
import br.com.mostrai.player.estado.DiarioBordo
import br.com.mostrai.player.estado.EstadoPlayer
import br.com.mostrai.player.kiosk.Watchdog
import br.com.mostrai.player.network.HeartbeatJson
import br.com.mostrai.player.network.MostraiApi
import br.com.mostrai.player.network.ResultadoHttp
import br.com.mostrai.player.network.Sincronizacao
import br.com.mostrai.player.operacao.RegistroOperacional
import br.com.mostrai.player.playlist.InstitucionalLocal
import br.com.mostrai.player.playlist.ItemPlaylist
import br.com.mostrai.player.playlist.Playlist
import br.com.mostrai.player.playlist.PlaylistCache
import br.com.mostrai.player.playlist.PlaylistRepositorio
import br.com.mostrai.player.playlist.PlaylistRepositorio.Origem
import br.com.mostrai.player.playlist.PosicaoNaPlaylist
import br.com.mostrai.player.playlist.RelogioConfiavel
import br.com.mostrai.player.playlist.RelogioJanela
import br.com.mostrai.player.playlist.ReposicionamentoPlaylist
import br.com.mostrai.player.proof.FilaProofOfPlay
import br.com.mostrai.player.provisionamento.Provisionador
import br.com.mostrai.player.ui.EstadoInstitucional
import br.com.mostrai.player.ui.InfoSuporte
import br.com.mostrai.player.ui.RotacaoTela
import br.com.mostrai.player.ui.TelaInstitucional
import br.com.mostrai.player.ui.TelaPinSaida
import br.com.mostrai.player.ui.TelaProvisionamento
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A única Activity do Player (contrato MVP):
 *
 * ```
 * BOOT → PROVISIONAR → CONFIG → PLAYLIST → CACHE → REPRODUZIR → CONFIRMAR
 *      → FUNCIONAR OFFLINE → SINCRONIZAR QUANDO A REDE VOLTA
 * ```
 *
 * O ciclo avança item a item em vez de usar a fila do ExoPlayer: só a
 * conclusão real conta, e cada exibição precisa terminar num STATE_ENDED
 * próprio, observável — é ali que o comprovante nasce.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var config: ConfigAparelho
    private lateinit var api: MostraiApi
    private lateinit var repositorio: PlaylistRepositorio
    private lateinit var fila: FilaProofOfPlay
    private lateinit var cacheMidia: CacheMidia
    private lateinit var diario: DiarioBordo
    private lateinit var sincronizacao: Sincronizacao
    private lateinit var provisionador: Provisionador
    private lateinit var relogio: RelogioConfiavel
    private lateinit var registro: RegistroOperacional
    private lateinit var institucionalLocal: InstitucionalLocal

    private lateinit var playerView: PlayerView
    private lateinit var institucional: TelaInstitucional
    private lateinit var telaProvisionamento: TelaProvisionamento
    private lateinit var telaPin: TelaPinSaida
    private lateinit var raiz: View

    /** Carrega o conteúdo de verdade e gira — ver [aplicarRotacaoEMargem]. */
    private lateinit var rotor: FrameLayout

    private var player: ExoPlayer? = null

    /** Player à parte do vídeo de abertura — ver [tocarIntroducao]. */
    private var introPlayer: ExoPlayer? = null

    private val handler = Handler(Looper.getMainLooper())

    private var playlist: Playlist = Playlist.VAZIA
    private var janelaIdAtual: String? = null
    private var relogioJanela: RelogioJanela? = null
    private var indice = 0

    /** execucaoId da exibição em andamento, ou null se o item não conta. */
    private var execucaoAtualId: String? = null

    /** criativoId do item comercial no ar — vai no heartbeat. */
    private var criativoAtualId: String? = null

    private var estadoAtual: EstadoPlayer = EstadoPlayer.IDLE

    /**
     * O que já está aplicado na tela (R11): trocar `layoutParams` dispara
     * `requestLayout()` mesmo com valores idênticos, e essa passada atravessa
     * o `TextureView` do vídeo. Só reaplica quando a margem muda de verdade.
     */
    private var margensAplicadas: MargensOverscan? = null

    /**
     * Impede dois GETs de playlist simultâneos (Parte 17): poll, virada de
     * hora, `playlist.atualizar` e volta da rede podem coincidir.
     */
    private val buscandoPlaylist = AtomicBoolean(false)

    /**
     * Pedido que chegou com uma busca em voo. **Não pode ser descartado**
     * (BUG-002): roda assim que a busca atual termina, preservando o
     * "forçado" mais forte que chegou.
     */
    private var buscaPendente = false
    private var buscaPendenteForcada = false

    /** Heartbeat de 15 s nunca se empilha: com a rede lenta, o próximo espera o anterior. */
    private val heartbeatEmVoo = AtomicBoolean(false)

    /**
     * Verdadeiro entre `onStart` e `onStop`. Corrotinas que terminam depois de
     * `onStop` não podem iniciar exibição: o player já foi liberado, e a
     * linha de proof-of-play que nasceria ficaria órfã (BUG-001).
     */
    private var iniciada = false

    /** Ciclo de reprodução rodando: aparelho provisionado e Activity na frente. */
    private var cicloAtivo = false

    /**
     * Incrementada a cada [tocarItemAtual]. `mostrarVideo` confere a geração
     * antes de aplicar o resultado — uma corrotina de um item anterior nunca
     * sobrescreve o item que já assumiu a tela.
     */
    private var geracaoReproducao = 0

    /** Itens seguidos que não conseguiram tocar — zera a cada exibição que termina. */
    private var falhasSeguidas = 0

    /** Origem da última busca de playlist — decide o cartão local e o estado. */
    private var ultimaOrigemFetch: Origem = Origem.NENHUMA

    /**
     * A programação comercial venceu sem uma nova do servidor: só o
     * institucional toca (offline não autoriza veiculação). Sai sozinho
     * quando uma playlist válida chega.
     */
    private var emFallback = false
    private var indiceFallback = 0

    /** Sessão operacional em curso ([RegistroOperacional]); escrita só na thread principal. */
    @Volatile
    private var sessaoAtual: String? = null

    /** Conta os ciclos que abriram sessão (só na thread principal): a sessão só fica com o ciclo que a pediu. */
    private var cicloOperacional = 0

    /** Último envio de sessões (uptime): a sessão aberta vai a cada 15 min, as encerradas na hora. */
    @Volatile
    private var ultimoEnvioOperacaoMs = 0L
    @Volatile
    private var ultimaFalhaOperacaoMs = Long.MIN_VALUE / 2

    /**
     * Diário numa fila de uma thread só: fora da thread principal (o vídeo
     * em TextureView depende dela) e na ordem em que os fatos aconteceram.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val filaDoDiario = Dispatchers.IO.limitedParallelism(1)

    /** Cobre a troca cartão → vídeo até o primeiro quadro; teto caso ele não avise. */
    private val esconderCartaoSobreVideo = Runnable {
        if (playerView.visibility == View.VISIBLE) institucional.visibility = View.GONE
    }

    private val avancarPorTempo = Runnable { avancar() }

    private val buscarPeriodicamente = object : Runnable {
        override fun run() {
            atualizarPlaylist(forcarReposicionamento = false)
            handler.postDelayed(this, Produto.INTERVALO_POLL_PLAYLIST_MS)
        }
    }

    /**
     * Sem servidor e sem última playlist válida (ou com ela vencida), tenta
     * de novo bem antes do poll de 15 min. Sem forçar reposicionamento: a
     * mesma janela de volta não pode reiniciar o institucional de reserva a
     * cada minuto (ciclo 3); janela nova e lista vazia reiniciam sozinhas.
     */
    private val retentarPlaylist = Runnable { atualizarPlaylist(forcarReposicionamento = false) }

    private val heartbeatPeriodico = object : Runnable {
        override fun run() {
            dispararHeartbeat()
            handler.postDelayed(this, Produto.INTERVALO_HEARTBEAT_MS)
        }
    }

    private val flushFilaPeriodico = object : Runnable {
        override fun run() {
            lifecycleScope.launch(Dispatchers.IO) {
                fila.tentarEnviar()
                enviarOperacao(forcar = false)
            }
            handler.postDelayed(this, Produto.INTERVALO_ENVIO_POP_MS)
        }
    }

    /**
     * Renova o sinal de vida do watchdog enquanto a Activity está na frente
     * (BUG-003): em operação normal a tela fica RESUMED por dias sem nenhum
     * callback de lifecycle.
     */
    private val renovarSinalDeVida = object : Runnable {
        override fun run() {
            Watchdog.registrarSinalDeVida(this@PlayerActivity)
            relogio.registrarPiso(relogioJanela)
            sessaoAtual?.let { id ->
                val servidor = servidorAgoraMs()
                lifecycleScope.launch(Dispatchers.IO) { registro.checkpoint(id, servidor) }
            }
            handler.postDelayed(this, INTERVALO_SINAL_DE_VIDA_MS)
        }
    }

    /** Reavalia o horário do ponto sem depender de rede. */
    private val checarHorario = object : Runnable {
        override fun run() {
            aplicarHorarioOperacional()
            handler.postDelayed(this, INTERVALO_HORARIO_MS)
        }
    }

    private val viradaDeHora = Runnable {
        atualizarPlaylist(forcarReposicionamento = false)
        agendarViradaDeHora()
    }

    /**
     * Rede voltou: heartbeat e fila na hora, e a playlist de novo se a atual
     * não veio do servidor (BUG-030: uma queda que atravessa a virada de
     * hora deixava a TV na playlist da hora anterior).
     */
    private val callbackConectividade = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post {
                if (!cicloAtivo) return@post
                cacheMidia.redeVoltou()
                lifecycleScope.launch(Dispatchers.IO) {
                    fila.redeVoltou()
                    enviarOperacao(forcar = true)
                }
                dispararHeartbeat()
                // onAvailable também dispara no registro do callback, no
                // boot, com a primeira busca já saindo.
                if (!buscandoPlaylist.get() && ultimaOrigemFetch != Origem.SERVIDOR) {
                    atualizarPlaylist(forcarReposicionamento = false)
                }
            }
        }
    }
    private var callbackRegistrado = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        config = ConfigAparelho(this)
        config.limparChavesObsoletas()
        diario = DiarioBordo(this)
        api = MostraiApi(config)
        // Chamado da thread da requisição: a credencial some ali mesmo, e só
        // a tela volta para a thread principal.
        api.aoRecusarCredencial = { chave ->
            if (config.esquecerCredencialSeFor(chave)) {
                registrarNoDiario(DiarioBordo.Codigo.AUTH_FALHOU, "credencial recusada (HTTP 401)")
                handler.post { aoPerderCredencial() }
            }
        }
        repositorio = PlaylistRepositorio(this, api)
        fila = FilaProofOfPlay(this, api, diario)
        cacheMidia = CacheMidia(this)
        relogio = RelogioConfiavel(this)
        institucionalLocal = InstitucionalLocal(this)
        registro = RegistroOperacional(this)
        sincronizacao = Sincronizacao(config, api, diario)
        provisionador = Provisionador(config, api, diario)

        raiz = findViewById(R.id.raiz)
        rotor = findViewById(R.id.rotor)
        playerView = findViewById(R.id.player)
        institucional = findViewById(R.id.institucional)
        telaProvisionamento = TelaProvisionamento(findViewById(R.id.telaProvisionamento), ::conectar)
        telaPin = TelaPinSaida(findViewById(R.id.telaPin), config, ::sairComAutorizacao)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        aplicarRotacaoEMargem()

        registrarNoDiario(DiarioBordo.Codigo.BOOT, "versão ${BuildConfig.VERSION_NAME}")
    }

    override fun onStart() {
        super.onStart()
        iniciada = true
        // Abrir o app (à mão, pelo boot ou pelo próprio watchdog) desfaz uma
        // saída autorizada anterior: a operação normal volta.
        Watchdog.rearmar(this)
        Watchdog.naFrente(this)
        Watchdog.registrarSinalDeVida(this)
        handler.postDelayed(renovarSinalDeVida, INTERVALO_SINAL_DE_VIDA_MS)
        if (introJaTocou) entrarEmOperacao() else tocarIntroducao()
    }

    override fun onResume() {
        super.onResume()
        esconderInterfaceDoSistema()
        Watchdog.registrarSinalDeVida(this)
    }

    override fun onStop() {
        super.onStop()
        iniciada = false
        handler.removeCallbacksAndMessages(null)
        // Saiu da frente sem PIN (HOME, outro app por cima): volta em segundos.
        // A saída por PIN já gravou a autorização, e o watchdog a respeita.
        if (!isChangingConfigurations) Watchdog.saiuDaFrente(this)
        pararCiclo()
        telaPin.esconder()
        // Só libera — nunca chama encerrarIntroducao(), que iniciaria um
        // ciclo novo bem no momento em que a Activity está parando.
        if (playerView.player === introPlayer) playerView.player = null
        introPlayer?.release()
        introPlayer = null
    }

    /**
     * Vídeo de abertura da marca — só no processo recém-iniciado. Num player
     * próprio: o `STATE_ENDED` dele não é exibição de anunciante nenhum.
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

    private fun encerrarIntroducao() {
        if (playerView.player === introPlayer) playerView.player = null
        introPlayer?.release()
        introPlayer = null
        entrarEmOperacao()
    }

    private fun entrarEmOperacao() {
        if (config.provisionado) iniciarCicloNormal() else mostrarProvisionamento()
    }

    // ---------------------------------------------------------- provisionamento

    private fun mostrarProvisionamento() {
        pararCiclo(MOTIVO_SEM_CREDENCIAL)
        telaPin.esconder()
        estadoAtual = EstadoPlayer.NOT_PROVISIONED
        playerView.visibility = View.GONE
        institucional.visibility = View.GONE
        telaProvisionamento.mostrar(config.dispositivoId)
    }

    private fun conectar(idDigitado: String, codigoDigitado: String) {
        val telaAnterior = config.dispositivoId
        lifecycleScope.launch {
            val resultado = withContext(Dispatchers.IO) {
                provisionador.provisionar(idDigitado, codigoDigitado).also {
                    // Outra tela: a última playlist guardada é da anterior e
                    // não pode servir de fallback offline para esta.
                    if (it is Provisionador.Resultado.Ok && telaAnterior != null && telaAnterior != config.dispositivoId) {
                        PlaylistCache(this@PlayerActivity).limpar()
                    }
                }
            }
            val mensagem = when (resultado) {
                is Provisionador.Resultado.Ok -> {
                    telaProvisionamento.esconder()
                    if (iniciada) iniciarCicloNormal()
                    return@launch
                }
                is Provisionador.Resultado.IdInvalido -> getString(R.string.prov_id_invalido)
                is Provisionador.Resultado.CodigoInvalido -> getString(R.string.prov_codigo_invalido)
                is Provisionador.Resultado.Invalido -> getString(R.string.prov_confira)
                is Provisionador.Resultado.Recusado -> getString(R.string.prov_recusado)
                is Provisionador.Resultado.Limitado -> getString(R.string.prov_limitado, resultado.segundos)
                is Provisionador.Resultado.SemConexao -> getString(R.string.prov_sem_conexao)
                is Provisionador.Resultado.NaoGravou -> getString(R.string.prov_nao_gravou)
            }
            telaProvisionamento.mostrarMensagem(mensagem)
            // Código recusado não serve mais — o operador digita outro. Sem
            // conexão, o mesmo código ainda vale (repetição curta do contrato).
            telaProvisionamento.liberar(apagarCodigo = resultado is Provisionador.Resultado.Recusado)
        }
    }

    /**
     * 401 numa rota autenticada: a credencial já foi apagada (contrato §4). A
     * fila de proof-of-play fica — a mesma tela reinstalada a envia.
     */
    private fun aoPerderCredencial() {
        if (iniciada && !config.provisionado) mostrarProvisionamento()
    }

    // ------------------------------------------------------------------- ciclo

    private fun iniciarCicloNormal() {
        if (cicloAtivo || !iniciada) return
        cicloAtivo = true
        telaProvisionamento.esconder()
        // Instalada agora: o primeiro heartbeat não pode dizer o contrário.
        if (estadoAtual == EstadoPlayer.NOT_PROVISIONED) estadoAtual = EstadoPlayer.IDLE

        // Todo (re)começo do ciclo — primeira abertura, volta do HOME, do
        // standby ou de uma reinstalação — cobre a tela até o primeiro item:
        // o player é novo e, sem isto, fica preto até a playlist responder.
        mostrarCartaoLocal(EstadoInstitucional.CARREGANDO)
        emFallback = false
        cacheMidia.proteger(playlist.itens + institucionalLocal.itens())
        abrirSessaoOperacional()

        criarPlayer()
        aplicarHorarioOperacional()

        atualizarPlaylist(forcarReposicionamento = true)
        // O primeiro sinal sai agora, não daqui a um ciclo (R10).
        dispararHeartbeat()
        handler.postDelayed(buscarPeriodicamente, Produto.INTERVALO_POLL_PLAYLIST_MS)
        handler.postDelayed(heartbeatPeriodico, Produto.INTERVALO_HEARTBEAT_MS)
        handler.postDelayed(flushFilaPeriodico, Produto.INTERVALO_ENVIO_POP_MS)
        handler.postDelayed(checarHorario, INTERVALO_HORARIO_MS)
        agendarViradaDeHora()

        lifecycleScope.launch(Dispatchers.IO) { fila.tentarEnviar() }
        runCatching {
            conectividade().registerDefaultNetworkCallback(callbackConectividade)
            callbackRegistrado = true
        }
    }

    /**
     * Para tudo o que o ciclo faz — temporizadores, rede, player — e encerra
     * a exibição em andamento sem comprovante (R5). Não mexe no sinal de
     * vida do watchdog.
     */
    private fun pararCiclo(motivo: String = MOTIVO_PAROU) {
        if (cicloAtivo) fecharSessaoOperacional(motivo)
        cicloAtivo = false
        // Invalida qualquer mostrarVideo ainda resolvendo cache.
        geracaoReproducao++
        listOf(
            avancarPorTempo, buscarPeriodicamente, retentarPlaylist, heartbeatPeriodico,
            flushFilaPeriodico, checarHorario, viradaDeHora, esconderCartaoSobreVideo,
        ).forEach(handler::removeCallbacks)
        if (callbackRegistrado) {
            runCatching { conectividade().unregisterNetworkCallback(callbackConectividade) }
            callbackRegistrado = false
        }
        cancelarExibicaoEmAndamento()
        criativoAtualId = null
        liberarPlayer()
    }

    private fun conectividade() =
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // ---------------------------------------------------------- tempo operacional

    /** O ciclo começou: uma sessão nova (e a anterior, se ficou aberta, é encerrada). */
    private fun abrirSessaoOperacional() {
        val dispositivo = config.dispositivoId
        val servidor = servidorAgoraMs()
        val meuCiclo = ++cicloOperacional
        lifecycleScope.launch(Dispatchers.IO) {
            val id = registro.abrir(dispositivo, servidor)
            val ficou = withContext(Dispatchers.Main) {
                // O ciclo pode ter parado (ou parado e recomeçado) enquanto a
                // sessão abria: sessão de um ciclo que já não é o atual seria
                // estendida pelo sinal de vida sem ter ciclo por trás.
                if (cicloAtivo && meuCiclo == cicloOperacional) {
                    sessaoAtual = id
                    true
                } else {
                    false
                }
            }
            if (!ficou && id != null) registro.fechar(id, RegistroOperacional.MOTIVO_INTERROMPIDA, servidor)
            enviarOperacao(forcar = true)
        }
    }

    /**
     * O ciclo parou: a sessão fecha com o motivo. Não depende do
     * lifecycleScope (que morre com a Activity): é um fato curto e precisa
     * chegar ao disco mesmo na saída por PIN.
     */
    private fun fecharSessaoOperacional(motivo: String) {
        val id = sessaoAtual ?: return
        sessaoAtual = null
        val servidor = servidorAgoraMs()
        Thread({ registro.fechar(id, motivo, servidor) }, "fechar-sessao").start()
    }

    /**
     * Manda as sessões ainda não confirmadas. A aberta vai a cada 15 min
     * (ela só anda); as encerradas e a volta da rede mandam na hora. Lotes
     * em sequência, nunca em paralelo. Bloqueante — chamar fora da thread
     * principal.
     */
    private fun enviarOperacao(forcar: Boolean) {
        val agora = android.os.SystemClock.elapsedRealtime()
        repeat(MAX_LOTES_OPERACAO) {
            val pendentes = registro.pendentes()
            if (pendentes.isEmpty()) return
            val soAberta = pendentes.all { it.aberta }
            if (!forcar && soAberta && agora - ultimoEnvioOperacaoMs < INTERVALO_ENVIO_OPERACAO_MS) return
            // Servidor sem a rota (404) ou fora: espera o intervalo, em vez
            // de a frota inteira bater nele a cada minuto.
            if (!forcar && agora - ultimaFalhaOperacaoMs < INTERVALO_ENVIO_OPERACAO_MS) return
            val resposta = api.enviarOperacao(pendentes)
            if (resposta !is ResultadoHttp.Ok) {
                ultimaFalhaOperacaoMs = agora
                return
            }
            ultimaFalhaOperacaoMs = Long.MIN_VALUE / 2
            ultimoEnvioOperacaoMs = agora
            registro.confirmar(pendentes, resposta.valor)
            if (soAberta || resposta.valor.isEmpty()) return
        }
    }

    // --------------------------------------------------------------- playlist

    /**
     * Busca a playlist (servidor → última válida → nada) e decide o índice —
     * a decisão mora em [ReposicionamentoPlaylist], testável sem Android.
     */
    private fun atualizarPlaylist(forcarReposicionamento: Boolean) {
        if (!cicloAtivo) return
        if (!buscandoPlaylist.compareAndSet(false, true)) {
            buscaPendente = true
            buscaPendenteForcada = buscaPendenteForcada || forcarReposicionamento
            return
        }
        handler.removeCallbacks(retentarPlaylist)
        lifecycleScope.launch {
            try {
                val resultado = withContext(Dispatchers.IO) { repositorio.buscar() }
                // 401: a credencial já foi apagada, e aoPerderCredencial leva à instalação.
                if (!cicloAtivo || resultado.origem == Origem.RECUSADA) return@launch

                val playlistAnterior = playlist
                val indiceAnterior = indice
                val trocouDeJanela = resultado.playlist.janelaId != janelaIdAtual

                playlist = resultado.playlist
                relogioJanela = resultado.relogio
                janelaIdAtual = resultado.playlist.janelaId
                relogio.registrarPiso(relogioJanela)
                if (resultado.origem == Origem.SERVIDOR) {
                    institucionalLocal.atualizar(playlist)
                    relogio.registrarSincronizacao(agoraConfiavelMs())
                }
                registrarOrigem(resultado)
                cacheMidia.proteger(playlist.itens + institucionalLocal.itens())
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
                // Lista vazia: cartão local já (BUG-022) — e um anúncio no ar
                // numa tela que acabou de ser suspensa para aqui (403).
                // Em fallback, uma playlist que voltou a valer assume na hora.
                if (decisao.reiniciarAgora || playlist.itens.isEmpty() || (emFallback && programacaoAutorizada())) {
                    reiniciarItemAgora()
                }
                // Sem nada, ou com a programação vencida (a busca da virada
                // falhou ou ainda trouxe a hora anterior): tenta de novo em
                // 1 min, não só no ciclo de 15 min — cada minuto em reserva
                // é tempo comercial perdido.
                if (resultado.origem == Origem.NENHUMA || (playlist.itens.isNotEmpty() && !programacaoAutorizada())) {
                    handler.postDelayed(retentarPlaylist, ESPERA_RETENTAR_PLAYLIST_MS)
                }
            } finally {
                buscandoPlaylist.set(false)
                if (buscaPendente) {
                    val forcada = buscaPendenteForcada
                    buscaPendente = false
                    buscaPendenteForcada = false
                    atualizarPlaylist(forcada)
                }
            }
        }
    }

    /**
     * Reentrada por posição temporal (item 7.1) — nunca por índice salvo.
     * Depois de um reboot sem internet, a âncora some, mas o relógio
     * confiável ([RelogioConfiavel]) ainda sabe a hora se o relógio da TV não
     * estiver atrás do último instante visto do servidor: a playlist retoma
     * de onde deveria, em vez de recomeçar do primeiro item.
     */
    private fun calcularIndiceInicial(): Int {
        val agora = agoraConfiavelMs() ?: return 0
        val inicioIso = playlist.janelaInicio ?: return 0
        val inicioMs = runCatching { OffsetDateTime.parse(inicioIso).toInstant().toEpochMilli() }
            .getOrNull() ?: return 0
        return PosicaoNaPlaylist.calcular(playlist.itens, agora - inicioMs)
    }

    private fun agoraConfiavelMs(): Long? = relogio.agoraMs(relogioJanela)

    /**
     * Só o relógio do servidor (âncora + monotônico), nunca o de parede:
     * é o que vai nos campos `*ServidorEm` das sessões operacionais. Null
     * sem âncora neste processo — o servidor fica com o relógio da TV como
     * informativo, sabendo que é dela.
     */
    private fun servidorAgoraMs(): Long? = relogioJanela?.takeIf { it.valida() }?.agoraDoServidorMs()

    /** Offline não autoriza veiculação: comercial só dentro da janela que o servidor deu. */
    private fun programacaoAutorizada(): Boolean = playlist.comercialAutorizadoEm(agoraConfiavelMs())

    private fun reiniciarItemAgora() {
        handler.removeCallbacks(avancarPorTempo)
        player?.stop()
        cancelarExibicaoEmAndamento()
        tocarItemAtual()
    }

    /**
     * Encerra o registro de uma exibição que não vai terminar (R5). Toda
     * saída que abandona [execucaoAtualId] passa por aqui.
     */
    private fun cancelarExibicaoEmAndamento() {
        val id = execucaoAtualId ?: return
        execucaoAtualId = null
        lifecycleScope.launch(Dispatchers.IO) { fila.registrarFalha(id) }
    }

    private fun registrarOrigem(resultado: PlaylistRepositorio.Resultado) {
        val anterior = ultimaOrigemFetch
        ultimaOrigemFetch = resultado.origem
        when (resultado.origem) {
            Origem.SERVIDOR -> lifecycleScope.launch(filaDoDiario) { diario.limparErros() }
            Origem.SUSPENSA -> if (anterior != Origem.SUSPENSA) {
                registrarNoDiario(DiarioBordo.Codigo.TELA_SUSPENSA, resultado.falha)
            }
            Origem.NENHUMA -> registrarNoDiario(DiarioBordo.Codigo.PLAYLIST_FALHOU, resultado.falha)
            // Rede caída não é erro do aparelho; playlist fora do contrato é.
            Origem.CACHE -> if (resultado.falha == "RESPOSTA_INVALIDA") {
                registrarNoDiario(DiarioBordo.Codigo.PLAYLIST_FALHOU, "playlist fora do contrato")
            }
            Origem.RECUSADA -> Unit
        }
    }

    private fun registrarNoDiario(codigo: DiarioBordo.Codigo, mensagem: String? = null) {
        lifecycleScope.launch(filaDoDiario) { diario.registrar(codigo, mensagem) }
    }

    /**
     * Acorda na virada da hora com um atraso derivado do ID da tela, para as
     * telas da rede não baterem juntas no servidor. A hora cheia é a do
     * SERVIDOR quando há âncora: com o relógio da TV adiantado 20 min, a
     * versão anterior buscava às 13:40 reais, recebia ainda a janela das 13h
     * e só pegava a das 14h no poll de 15 min, toda hora.
     */
    private fun agendarViradaDeHora() {
        val agoraMs = agoraConfiavelMs() ?: System.currentTimeMillis()
        handler.postDelayed(viradaDeHora, atrasoAteViradaMs(agoraMs, config.atrasoViradaSegundos()))
    }

    /**
     * Baixa de antemão o que ainda não está em cache, sem atrasar a
     * reprodução. Programação vencida não vale a banda: só o institucional,
     * que é o que pode tocar.
     */
    private fun preAquecerCache(playlist: Playlist) {
        val itens = if (programacaoAutorizada()) playlist.itens else playlist.institucionais() + institucionalLocal.itens()
        lifecycleScope.launch(Dispatchers.IO) { cacheMidia.preAquecer(itens) }
    }

    // ---------------------------------------------------------------- horário

    /**
     * Fora do horário do ponto a tela para de vender: nenhum item comercial
     * toca e nenhum proof-of-play nasce. Decidido no aparelho, com a última
     * config — a loja abre e fecha no horário mesmo sem internet.
     */
    private fun aplicarHorarioOperacional() {
        if (!cicloAtivo) return
        // Hora do servidor quando há âncora; o relógio da TV só se não
        // estiver atrás do que o servidor já mostrou. Sem saber a hora, o
        // padrão é permissivo: tela apagada em horário comercial é receita
        // perdida; acesa fora de hora é só desperdício.
        val agora = agoraConfiavelMs()?.let(Instant::ofEpochMilli)
        val dentro = agora == null || config.horarioOperacional().estaDentro(agora)
        if (dentro) {
            if (estadoAtual == EstadoPlayer.OUT_OF_SCHEDULE) {
                estadoAtual = EstadoPlayer.IDLE
                atualizarPlaylist(forcarReposicionamento = true)
            }
            return
        }

        if (estadoAtual == EstadoPlayer.OUT_OF_SCHEDULE) return

        estadoAtual = EstadoPlayer.OUT_OF_SCHEDULE
        registrarNoDiario(DiarioBordo.Codigo.FORA_DO_HORARIO)
        // BUG-025: um mostrarVideo ainda resolvendo o cache voltaria depois
        // disto e tocaria o anúncio com a loja fechada.
        geracaoReproducao++
        handler.removeCallbacks(avancarPorTempo)
        player?.stop()
        cancelarExibicaoEmAndamento()
        criativoAtualId = null
        mostrarCartaoLocal(EstadoInstitucional.CARTAO)
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

                // O cartão sai exatamente quando o vídeo novo tem quadro: sem
                // piscar preto e sem mostrar o último quadro do vídeo anterior.
                override fun onRenderedFirstFrame() {
                    handler.removeCallbacks(esconderCartaoSobreVideo)
                    esconderCartaoSobreVideo.run()
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "falha ao reproduzir item $indice", error)
                    val id = execucaoAtualId
                    execucaoAtualId = null
                    criativoAtualId = null
                    estadoAtual = EstadoPlayer.PLAYBACK_ERROR
                    registrarNoDiario(DiarioBordo.Codigo.PLAYBACK_FALHOU, error.errorCodeName)
                    // Falha de reprodução NÃO é exibição: a linha nunca teve
                    // terminadoEm, não é uma alegação de exibição completa.
                    if (id != null) lifecycleScope.launch(Dispatchers.IO) { fila.registrarFalha(id) }
                    avancarAposFalha()
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
        if (!iniciada || !cicloAtivo) return
        if (foraDoHorario()) return

        val minhaGeracao = ++geracaoReproducao
        if (playlist.itens.isNotEmpty() && !programacaoAutorizada()) {
            tocarInstitucional(minhaGeracao)
            return
        }
        if (emFallback) {
            emFallback = false
            indiceFallback = 0
        }

        val item = playlist.itens.getOrNull(indice) ?: run {
            indice = 0
            playlist.itens.firstOrNull()
        } ?: run {
            // Nada para tocar (BUG-022): cartão local, e o estado diz por quê.
            execucaoAtualId = null
            criativoAtualId = null
            val suspensa = ultimaOrigemFetch == Origem.SUSPENSA
            mostrarCartaoLocal(if (suspensa) EstadoInstitucional.CARTAO else EstadoInstitucional.SEM_CONTEUDO)
            estadoAtual = if (suspensa) EstadoPlayer.IDLE else EstadoPlayer.NO_PLAYLIST
            return
        }

        // Só a url decide: o institucional da rede com url toca como qualquer
        // mídia; sem url, cartão local pelo tempo do item (contrato §7).
        if (item.url.isNullOrBlank()) {
            execucaoAtualId = null
            criativoAtualId = null
            mostrarCartaoDoItem(item)
        } else {
            mostrarVideo(item, minhaGeracao, fallback = false)
        }
    }

    /**
     * A janela autorizada acabou e não veio outra (sem internet, servidor
     * fora): o comercial vencido NÃO repete — offline não autoriza
     * veiculação, e um comprovante tardio seria creditado à hora errada. A
     * tela segue com o institucional da Mostraí guardado na TV, sem
     * comprovante; sem nenhum, o cartão da marca. Uma playlist válida do
     * servidor tira a tela daqui ([atualizarPlaylist]).
     */
    private fun tocarInstitucional(minhaGeracao: Int) {
        if (!emFallback) {
            emFallback = true
            indiceFallback = 0
            registrarNoDiario(DiarioBordo.Codigo.PROGRAMACAO_EXPIRADA, playlist.janelaId)
        }
        execucaoAtualId = null
        criativoAtualId = null
        estadoAtual = EstadoPlayer.NO_PLAYLIST
        val lista = institucionalLocal.itens()
        if (lista.isEmpty()) {
            mostrarCartaoLocal(EstadoInstitucional.CARTAO)
            handler.postDelayed(avancarPorTempo, ESPERA_EM_FALLBACK_SEM_MIDIA_MS)
            return
        }
        mostrarVideo(lista[Math.floorMod(indiceFallback, lista.size)], minhaGeracao, fallback = true)
    }

    private fun mostrarVideo(item: ItemPlaylist, minhaGeracao: Int, fallback: Boolean) {
        val playlistDoItem = playlist
        // Mídia que ainda vai baixar: o cartão cobre a espera, em vez do
        // último quadro do anúncio anterior congelado na tela.
        if (!cacheMidia.emCache(item) && institucional.visibility != View.VISIBLE) {
            mostrarCartaoLocal(EstadoInstitucional.CARTAO)
        }
        lifecycleScope.launch {
            val resolucao = withContext(Dispatchers.IO) { cacheMidia.resolucao(item) }
            if (minhaGeracao != geracaoReproducao) return@launch
            val arquivoLocal = resolucao.arquivo

            // Hash divergente é mídia comprovadamente errada: tocar a URL
            // remota seria servir o arquivo que acabou de ser rejeitado (R3).
            // Sem rede, a URL remota é igualmente inalcançável: pula na hora,
            // em vez de dezenas de segundos de tela preta por item.
            // O download pode ter levado minutos: a janela que autorizou o
            // comercial pode ter acabado no meio. Confere de novo antes do
            // comprovante e do play().
            if (!fallback && !playlistDoItem.comercialAutorizadoEm(agoraConfiavelMs())) {
                tocarItemAtual()
                return@launch
            }

            if (arquivoLocal == null && !resolucao.podeTocarDaUrlRemota) {
                if (resolucao.falha is CacheMidia.Falha.HashDivergente) {
                    estadoAtual = EstadoPlayer.DOWNLOAD_ERROR
                    registrarNoDiario(DiarioBordo.Codigo.MIDIA_HASH_DIVERGENTE, item.criativoId)
                }
                avancarAposFalha()
                return@launch
            }

            // A linha da fila nasce ANTES do play(), mas DEPOIS do download:
            // sair no meio de um download longo não deixa linha órfã.
            val id = if (fallback) null else withContext(Dispatchers.IO) { fila.registrarInicio(item, playlistDoItem) }
            if (minhaGeracao != geracaoReproducao) {
                // Um item mais novo já assumiu a tela: esta linha nunca teve
                // terminadoEm, descartá-la não é perda.
                if (id != null) lifecycleScope.launch(Dispatchers.IO) { fila.registrarFalha(id) }
                return@launch
            }

            val exo = player ?: run {
                if (id != null) lifecycleScope.launch(Dispatchers.IO) { fila.registrarFalha(id) }
                return@launch
            }

            execucaoAtualId = id
            criativoAtualId = if (item.contabiliza && !fallback) item.criativoId else null
            estadoAtual = if (fallback) EstadoPlayer.NO_PLAYLIST else EstadoPlayer.PLAYING

            // O cartão (se estiver na tela) só sai no primeiro quadro do vídeo.
            playerView.visibility = View.VISIBLE
            handler.removeCallbacks(esconderCartaoSobreVideo)
            handler.postDelayed(esconderCartaoSobreVideo, TETO_CARTAO_SOBRE_VIDEO_MS)

            val uri = if (arquivoLocal != null) Uri.fromFile(arquivoLocal) else Uri.parse(item.url!!)
            exo.setMediaItem(MediaItem.fromUri(uri))
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    private fun mostrarCartaoDoItem(item: ItemPlaylist) {
        falhasSeguidas = 0
        mostrarCartaoLocal(EstadoInstitucional.CARTAO)
        if (estadoAtual != EstadoPlayer.PLAYBACK_ERROR && estadoAtual != EstadoPlayer.DOWNLOAD_ERROR) {
            estadoAtual = EstadoPlayer.IDLE
        }
        val duracao = if (item.duracaoSegundos > 0) item.duracaoSegundos else 10
        handler.postDelayed(avancarPorTempo, duracao * 1000L)
    }

    private fun mostrarCartaoLocal(estado: EstadoInstitucional) {
        handler.removeCallbacks(esconderCartaoSobreVideo)
        playerView.visibility = View.GONE
        player?.stop()
        institucional.estado = estado
        institucional.visibility = View.VISIBLE
    }

    /** Chamado apenas no STATE_ENDED: é aqui que a exibição vira comprovante. */
    private fun concluirExibicao() {
        falhasSeguidas = 0
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

    /**
     * Pula o item que não tocou — mas não em laço (BUG-005): depois de uma
     * volta completa sem nenhuma exibição, mostra o cartão local e espera
     * antes de tentar de novo.
     */
    private fun avancarAposFalha() {
        falhasSeguidas++
        val tamanho = if (emFallback) institucionalLocal.itens().size else playlist.itens.size
        if (falhasSeguidas < tamanho) {
            avancar()
            return
        }
        falhasSeguidas = 0
        handler.removeCallbacks(avancarPorTempo)
        mostrarCartaoLocal(EstadoInstitucional.SEM_CONTEUDO)
        handler.postDelayed(avancarPorTempo, ESPERA_APOS_VOLTA_SEM_EXIBICAO_MS)
    }

    private fun avancar() {
        if (foraDoHorario()) return
        if (emFallback) {
            indiceFallback++
            tocarItemAtual() // reavalia: uma programação válida pode ter chegado
            return
        }
        if (playlist.itens.isEmpty()) {
            tocarItemAtual() // mostra o cartão local (BUG-022)
            return
        }
        indice = (indice + 1) % playlist.itens.size
        tocarItemAtual()
    }

    // --------------------------------------------------------------- heartbeat

    private fun dispararHeartbeat() {
        if (!cicloAtivo || !heartbeatEmVoo.compareAndSet(false, true)) return
        val estado = estadoAtual
        val versao = config.configVersionAplicada
        val criativo = criativoAtualId
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val resumo = fila.resumo()
                if (resumo.aguardandoEnvio >= FilaProofOfPlay.LIMIAR_ALERTA) {
                    diario.registrar(DiarioBordo.Codigo.FILA_LIMIAR, "${resumo.aguardandoEnvio} eventos na fila")
                }
                val erro = diario.ultimoErro()?.let { HeartbeatJson.Erro(it.codigo, it.mensagem, it.emIso) }
                val efeitos = sincronizacao.heartbeat(
                    HeartbeatJson.Corpo(
                        estado = estado,
                        configVersionAplicada = versao,
                        criativoId = criativo,
                        erro = erro,
                        filaPendentes = resumo.aguardandoEnvio,
                        filaMaisAntigoEm = resumo.maisAntigoMs?.let {
                            OffsetDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()).toString()
                        },
                    )
                )
                withContext(Dispatchers.Main) { aplicarEfeitos(efeitos) }
            } finally {
                heartbeatEmVoo.set(false)
            }
        }
    }

    /**
     * Config nova: margens na hora — só o padding do quadro, sem reiniciar a
     * Activity, o item ou o vídeo (contrato §6) — e horário reavaliado.
     */
    private fun aplicarEfeitos(efeitos: Sincronizacao.Efeitos) {
        if (!cicloAtivo) return
        if (efeitos.configAplicada) {
            aplicarRotacaoEMargem()
            aplicarHorarioOperacional()
        }
        if (efeitos.atualizarPlaylist) atualizarPlaylist(forcarReposicionamento = false)
    }

    // ------------------------------------------------------------------- tela

    /**
     * Rotação fixa do APK + margens da config, aplicadas a `rotor` depois que
     * `raiz` foi medida (por isso o `post`). R11: só quando a margem mudou.
     */
    private fun aplicarRotacaoEMargem() {
        val margens = config.margens
        if (margens == margensAplicadas) return
        margensAplicadas = margens
        raiz.post { RotacaoTela.aplicar(raiz, rotor, margens, Produto.ROTACAO_GRAUS) }
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

    // ----------------------------------------------------------------- saída

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (telaPin.visivel) telaPin.registrarAtividade()
        if (telaProvisionamento.aoTeclar(event) || telaPin.aoTeclar(event)) return true
        // As setas não se remapeiam: o conteúdo gira junto com a TV montada de
        // lado, então o layout já está de pé para quem olha, e a ViewRootImpl
        // move o foco pela tecla original.
        return super.dispatchKeyEvent(event)
    }

    /**
     * VOLTAR é a tentativa de saída que o app controla (HOME não chega a
     * aplicativo nenhum — dela cuida o watchdog). No Player, pede o PIN; com
     * o PIN aberto, fecha o pedido; na instalação, não faz nada (BUG-027:
     * um toque acidental no controle da loja não pode derrubar o Player).
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode != KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        // VOLTAR segurado repete: abriria e fecharia o pedido de PIN a cada repetição.
        if ((event?.repeatCount ?: 0) > 0) return true
        when {
            telaPin.visivel -> telaPin.esconder()
            cicloAtivo -> if (telaPin.pedir()) preencherInfoSuporte()
        }
        return true
    }

    /** Monta o bloco técnico fora da thread principal (consultas de disco). */
    private fun preencherInfoSuporte() {
        val playlistAgora = playlist
        val agora = agoraConfiavelMs()
        val online = runCatching { conectividade().activeNetworkInfo?.isConnected == true }.getOrDefault(false)
        lifecycleScope.launch {
            val dados = withContext(Dispatchers.IO) {
                val (emCache, total) = cacheMidia.disponiveis(playlistAgora.itens)
                val (sessoes, tempo) = registro.resumoPendente()
                InfoSuporte.Dados(
                    dispositivoId = config.dispositivoId,
                    versao = "${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}",
                    online = online,
                    ultimaSincronizacaoMs = relogio.ultimaSincronizacaoMs(),
                    programacaoValidaAteMs = playlistAgora.validaAteMs(),
                    programacaoAutorizadaAgora = playlistAgora.comercialAutorizadoEm(agora),
                    midiaEmCache = emCache,
                    midiaTotal = total,
                    institucionalGuardado = institucionalLocal.itens().size,
                    espacoSuficiente = cacheMidia.espacoLivre() >= cacheMidia.reservaBytes(),
                    comprovantesPendentes = fila.resumo().aguardandoEnvio,
                    sessoesPendentes = sessoes,
                    tempoOperacionalPendenteMs = tempo,
                )
            }
            telaPin.mostrarInfo(InfoSuporte.texto(dados))
        }
    }

    /** PIN certo: o watchdog não reabre, e o Player fecha normalmente (contrato §6). */
    private fun sairComAutorizacao() {
        Watchdog.autorizarSaida(this)
        pararCiclo(MOTIVO_SAIDA_PIN)
        finish()
    }

    internal companion object {
        const val TAG = "MostraiPlayer"

        /**
         * Sobrevive a recriação da Activity (não a reinício do processo) — o
         * vídeo de abertura só faz sentido no boot de verdade.
         */
        var introJaTocou = false

        const val INTERVALO_HORARIO_MS = 60_000L

        /** Bem abaixo de [Watchdog.TOLERANCIA_MS], com folga para atraso do looper. */
        const val INTERVALO_SINAL_DE_VIDA_MS = 30_000L

        const val MOTIVO_PAROU = "parou"
        const val MOTIVO_SAIDA_PIN = "saida_pin"
        const val MOTIVO_SEM_CREDENCIAL = "sem_credencial"

        /** A sessão aberta vai ao servidor a cada 15 min; as encerradas, na hora. */
        const val INTERVALO_ENVIO_OPERACAO_MS = 15 * 60_000L
        const val MAX_LOTES_OPERACAO = 10

        /** Pausa depois de uma volta inteira da playlist sem nenhuma exibição. */
        const val ESPERA_APOS_VOLTA_SEM_EXIBICAO_MS = 10_000L

        /** Sem servidor e sem última playlist válida: quanto esperar para tentar de novo. */
        const val ESPERA_RETENTAR_PLAYLIST_MS = 60_000L

        /** Programação vencida e nenhum institucional guardado: reavalia a cada minuto. */
        const val ESPERA_EM_FALLBACK_SEM_MIDIA_MS = 60_000L

        /** O cartão sai no primeiro quadro; se o aviso não vier, sai depois disto. */
        const val TETO_CARTAO_SOBRE_VIDEO_MS = 3_000L

        /**
         * Até a próxima hora cheia (UTC — o Brasil tem fuso de hora inteira)
         * mais o atraso da tela. Pura, para teste.
         */
        fun atrasoAteViradaMs(agoraMs: Long, atrasoTelaSegundos: Int): Long {
            val hora = 60 * 60 * 1000L
            val proxima = (Math.floorDiv(agoraMs, hora) + 1) * hora
            return (proxima - agoraMs + atrasoTelaSegundos * 1000L).coerceAtLeast(1_000L)
        }
    }
}
