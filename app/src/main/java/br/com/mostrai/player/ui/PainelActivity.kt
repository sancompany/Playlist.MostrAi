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
import br.com.mostrai.player.config.ConfigAparelho
import br.com.mostrai.player.network.EstadoRede
import br.com.mostrai.player.network.MostraiApi
import br.com.mostrai.player.proof.FilaProofOfPlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Painel de manutenção acessível na própria TV, sem teclado.
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

        // Mesma compensação de rotação do player (RotacaoTela) — sem isso o
        // painel apareceria de lado no mesmo aparelho que o player já
        // compensa.
        raiz.post { RotacaoTela.aplicar(raiz, rotor, config.margemVmin, config.rotacaoTela) }
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

    private fun digitar(d: Int) {
        val tamanho = tamanhoPin()
        if (digitado.length >= tamanho) return
        digitado.append(d)
        display.text = mascara()
        erro.visibility = View.INVISIBLE

        if (digitado.length == tamanho) {
            if (digitado.toString() == config.pinPainel) {
                mostrarInformacoes()
            } else {
                digitado.setLength(0)
                display.text = mascara()
                erro.text = getString(R.string.painel_pin_errado)
                erro.visibility = View.VISIBLE
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
            appendLine()
            appendLine("Tela .............. ${config.dispositivoId ?: "—"}")
            appendLine("Chave ............. ${resumirChave(config.chaveAparelho)}")
            appendLine("Servidor .......... ${config.baseUrl ?: "—"}")
            appendLine("Provisionado ...... ${if (config.provisionado) "sim" else "não"}")
            appendLine("Margem (vmin) ..... ${config.margemVmin}")
            appendLine("Rotação da tela .... ${config.rotacaoTela}°")
            appendLine("Atraso da virada .. ${config.atrasoViradaSegundos()}s")
            appendLine()
            appendLine("Contrato do servidor  ${if (EstadoRede.contratoNovo) "novo" else "antigo (degradado)"}")
            appendLine("Última playlist ..... ${EstadoRede.ultimaOrigem}")
            EstadoRede.ultimoErroAparelho?.let { appendLine("Erro do aparelho .... HTTP $it") }
            EstadoRede.ultimaFalhaTransitoria?.let { appendLine("Última falha de rede . $it") }
            appendLine()
            appendLine("Proof-of-play pendente  carregando…")
            if (config.pinPainel == ConfigAparelho.PIN_PROVISORIO) {
                appendLine()
                appendLine("ATENÇÃO: PIN ainda é o provisório de fábrica.")
            }
        }

        // Contagem em SQLite: mesmo sendo uma consulta pequena, E/S de disco
        // não roda na thread principal — é a própria política do Android
        // (StrictMode acusa isso em build de depuração).
        lifecycleScope.launch {
            val fila = FilaProofOfPlay(this@PainelActivity, MostraiApi(config))
            val (pendentes, perdas) = withContext(Dispatchers.IO) { fila.pendentes() to fila.perdas() }
            info.text = info.text.toString().replace(
                "Proof-of-play pendente  carregando…",
                "Proof-of-play pendente  $pendentes\nEventos perdidos ...... $perdas",
            )
        }
    }

    /** Nunca mostra a chave inteira na tela de um comércio. */
    private fun resumirChave(chave: String?): String {
        if (chave.isNullOrBlank()) return "—"
        return if (chave.length <= 8) "••••" else "${chave.take(4)}…${chave.takeLast(4)}"
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
