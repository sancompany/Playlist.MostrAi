package br.com.mostrai.player.ui

import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import br.com.mostrai.player.BuildConfig
import br.com.mostrai.player.R
import br.com.mostrai.player.cache.CacheMidia
import br.com.mostrai.player.config.ConfigAparelho
import br.com.mostrai.player.estado.DiarioBordo
import br.com.mostrai.player.kiosk.Kiosk
import br.com.mostrai.player.network.EstadoRede
import br.com.mostrai.player.network.HeartbeatJson
import br.com.mostrai.player.network.MostraiApi
import br.com.mostrai.player.proof.FilaProofOfPlay
import br.com.mostrai.player.update.Atualizador
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Painel de manutenção acessível na própria TV, sem teclado.
 *
 * **Só diagnóstico.** Não edita configuração: depois de provisionada, tudo
 * que é cotidiano numa tela vem do admin da Mostraí. O dono do ponto não
 * configura URL, chave, margem, horário nem atualização — e o técnico em
 * campo tem o `mostrai-config.json` do pendrive e os extras de `adb` para
 * emergência, que são caminhos deliberadamente fora do alcance de quem passa
 * na loja.
 *
 * O PIN é digitado numa grade navegável pelo D-pad do controle remoto, porque
 * controle de Android TV normalmente não tem teclado numérico.
 */
class PainelActivity : AppCompatActivity() {

    private lateinit var config: ConfigAparelho
    private lateinit var raiz: View
    private lateinit var rotor: FrameLayout
    private lateinit var display: TextView
    private lateinit var erro: TextView
    private lateinit var grupoPin: View
    private lateinit var grupoInfo: ScrollView

    private val digitado = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_painel)

        config = ConfigAparelho(this)
        raiz = findViewById(R.id.raiz)
        rotor = findViewById(R.id.rotor)
        display = findViewById(R.id.display)
        erro = findViewById(R.id.erroPin)
        grupoPin = findViewById(R.id.grupoPin)
        grupoInfo = findViewById(R.id.grupoInfo)

        display.text = mascara()
        montarTeclado(findViewById(R.id.teclado))
        mostrarBloqueioSeHouver()

        // Mesma compensação de rotação do player (RotacaoTela) — sem isso o
        // painel apareceria de lado no mesmo aparelho que o player já
        // compensa.
        raiz.post { RotacaoTela.aplicar(raiz, rotor, config.margensOverscan, config.rotacaoTela) }
    }

    private fun tamanhoPin(): Int = ConfigAparelho.TAMANHO_PIN

    private fun montarTeclado(grade: GridLayout) {
        for (d in 0..9) {
            val tecla = TextView(this).apply {
                text = d.toString()
                textSize = 26f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColorStateList(context, R.color.cor_tecla))
                setBackgroundResource(R.drawable.fundo_tecla)
                isFocusable = true
                isFocusableInTouchMode = true
                setOnClickListener { digitar(d) }
            }
            val params = GridLayout.LayoutParams().apply {
                width = resources.getDimensionPixelSize(R.dimen.tecla)
                height = resources.getDimensionPixelSize(R.dimen.tecla)
                setMargins(6, 6, 6, 6)
            }
            grade.addView(tecla, params)
            if (d == 0) tecla.requestFocus()
        }
    }

    /**
     * Sem isto são 10.000 combinações digitáveis num controle remoto sem
     * custo nenhum — qualquer pessoa com paciência abre o painel de qualquer
     * tela da rede. O bloqueio cresce a cada erro depois do terceiro e zera
     * no acerto; nunca é permanente, porque trancar a manutenção de uma tela
     * em campo seria pior que o ataque que isso evita.
     */
    private fun mostrarBloqueioSeHouver(): Boolean {
        val restanteMs = config.pinBloqueadoPorMs()
        if (restanteMs <= 0L) return false
        erro.text = getString(R.string.painel_pin_bloqueado, (restanteMs / 1000).toInt() + 1)
        erro.visibility = View.VISIBLE
        return true
    }

    private fun digitar(d: Int) {
        if (mostrarBloqueioSeHouver()) return

        val tamanho = tamanhoPin()
        if (digitado.length >= tamanho) return
        digitado.append(d)
        display.text = mascara()
        erro.visibility = View.INVISIBLE

        if (digitado.length == tamanho) {
            if (digitado.toString() == config.pinPainel) {
                config.registrarPinCerto()
                mostrarInformacoes()
            } else {
                config.registrarPinErrado()
                digitado.setLength(0)
                display.text = mascara()
                if (!mostrarBloqueioSeHouver()) {
                    erro.text = getString(R.string.painel_pin_errado)
                    erro.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun mascara(): String =
        (0 until tamanhoPin()).joinToString(" ") { if (it < digitado.length) "•" else "·" }

    private fun mostrarInformacoes() {
        grupoPin.visibility = View.GONE
        grupoInfo.visibility = View.VISIBLE

        val info = findViewById<TextView>(R.id.info)
        info.text = buildString {
            appendLine("MOSTRAÍ PLAYER ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Contrato do player .. ${HeartbeatJson.CONTRATO}")
            appendLine()
            appendLine("Tela .............. ${resumirId(config.dispositivoId)}")
            appendLine("Chave ............. ${resumirChave(config.chaveAparelho)}")
            appendLine("Servidor .......... ${config.baseUrl ?: "—"}")
            appendLine("Provisionado ...... ${if (config.provisionado) "sim" else "não"}")
            appendLine("Backend V2 ........ ${if (config.backendV2Disponivel) "sim" else "não detectado"}")
            appendLine("Config aplicada ... versão ${config.configVersionAplicada}")
            appendLine()
            appendLine(
                "Margem (vmin) ..... topo ${config.margemVminTopo} · base ${config.margemVminBase} · " +
                    "esq ${config.margemVminEsquerda} · dir ${config.margemVminDireita}"
            )
            appendLine("Rotação da tela ... ${config.rotacaoTela}°")
            appendLine("Regime .......... .. ${config.horarioOperacional().regime}")
            appendLine("Fuso .............. ${config.horarioOperacional().timezone}")
            appendLine("Atraso da virada .. ${config.atrasoViradaSegundos()}s")
            appendLine()
            appendLine("Contrato do servidor  ${if (EstadoRede.contratoNovo) "novo" else "antigo (degradado)"}")
            appendLine("Última playlist ..... ${EstadoRede.ultimaOrigem}")
            appendLine("Playlist OK em ...... ${config.ultimaPlaylistOkEm ?: "—"}")
            EstadoRede.ultimoErroAparelho?.let { appendLine("Erro do aparelho .... HTTP $it") }
            EstadoRede.ultimaFalhaTransitoria?.let { appendLine("Última falha de rede . $it") }
            appendLine()
            appendLine("Device Owner ........ ${if (Kiosk.ehDeviceOwner(this@PainelActivity)) "sim" else "não"}")
            appendLine()
            appendLine("Carregando diagnóstico…")
            if (config.pinPainel == ConfigAparelho.PIN_PROVISORIO) {
                appendLine()
                appendLine("ATENÇÃO: PIN ainda é o provisório de fábrica.")
            }
        }

        // Contagem em SQLite e leitura de diretório: E/S de disco não roda na
        // thread principal — é a própria política do Android (StrictMode
        // acusa isso em build de depuração).
        lifecycleScope.launch {
            val texto = withContext(Dispatchers.IO) { diagnosticoPesado() }
            info.text = info.text.toString().replace("Carregando diagnóstico…", texto)
        }
    }

    private fun diagnosticoPesado(): String {
        val fila = FilaProofOfPlay(this, MostraiApi(config))
        val resumo = fila.resumo()
        val cache = CacheMidia(this)
        val atualizador = Atualizador(this, DiarioBordo(this))
        val ultimoErro = DiarioBordo(this).ultimoErro()

        return buildString {
            appendLine("Proof-of-play ....... ${resumo.aguardandoEnvio} aguardando envio")
            appendLine("Fila total .......... ${resumo.total} (quarentena: ${resumo.quarentena})")
            appendLine("Mais antigo ......... ${resumo.maisAntigoMs?.let(::formatarIdade) ?: "—"}")
            appendLine("Eventos perdidos .... ${fila.perdas()}")
            appendLine()
            appendLine("Cache ............... ${cache.arquivos()} arquivos, ${cache.tamanhoBytes() / 1_048_576} MB")
            appendLine()
            appendLine("Atualização ......... ${atualizador.estado}")
            if (atualizador.buildAlvo > 0) {
                appendLine("Versão alvo ......... ${atualizador.versaoAlvo} (build ${atualizador.buildAlvo})")
                appendLine("Obrigatória ......... ${if (atualizador.obrigatorio) "sim" else "não"}")
            }
            appendLine()
            if (ultimoErro == null) {
                appendLine("Último erro ......... nenhum")
            } else {
                appendLine("Último erro ......... ${ultimoErro.codigo} em ${ultimoErro.emIso}")
                ultimoErro.mensagem?.let { appendLine("  $it") }
            }
        }.trimEnd()
    }

    private fun formatarIdade(ms: Long): String {
        val horas = (System.currentTimeMillis() - ms) / 3_600_000
        return if (horas < 1) "menos de 1h" else "${horas}h atrás"
    }

    /** Nunca mostra a chave inteira na tela de um comércio. */
    private fun resumirChave(chave: String?): String {
        if (chave.isNullOrBlank()) return "—"
        return if (chave.length <= 8) "••••" else "${chave.take(4)}…${chave.takeLast(4)}"
    }

    /** O id não é segredo, mas a tela fica num comércio — mostra só o suficiente para conferir. */
    private fun resumirId(id: String?): String {
        if (id.isNullOrBlank()) return "—"
        return if (id.length <= 12) id else "${id.take(8)}…${id.takeLast(4)}"
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
