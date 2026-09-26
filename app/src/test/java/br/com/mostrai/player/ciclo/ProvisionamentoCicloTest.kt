package br.com.mostrai.player.ciclo

import android.view.View
import android.widget.TextView
import br.com.mostrai.player.R
import br.com.mostrai.player.cache.ServidorDeTeste
import br.com.mostrai.player.config.ConfigAparelho
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.LooperMode

/** Primeira abertura e reinstalação (contrato §3 e §4), pela tela da TV. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ProvisionamentoCicloTest {

    private lateinit var h: Harness

    @Before
    fun preparar() {
        h = Harness()
    }

    @After
    fun encerrar() = h.encerrar()

    private val chave = "k".repeat(43)

    @Test
    fun `aparelho sem credencial mostra so a instalacao e nao fala com rotas autenticadas`() {
        val atividade = h.subir().get()
        h.avancar(20_000L)

        assertEquals(View.VISIBLE, h.vista<View>(atividade, R.id.telaProvisionamento).visibility)
        assertEquals("M-", h.vista<TextView>(atividade, R.id.campoId).text.toString())
        assertEquals("", h.vista<TextView>(atividade, R.id.campoCodigo).text.toString())
        assertTrue("nenhuma chamada sem credencial: ${h.servidor.recebidas}", h.servidor.recebidas.isEmpty())
    }

    @Test
    fun `setas do controle andam na direcao que o instalador ve, com a tela girada`() {
        // A TV está montada de lado e o conteúdo gira junto: para quem olha,
        // o layout está de pé. A ViewRootImpl move o foco pela tecla ORIGINAL
        // (focusSearch nas coordenadas do layout, sem a rotação) — que são as
        // mesmas que o instalador vê. Não há o que remapear.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        h.pularIntroducao()
        val atividade = h.subir().get()
        h.avancar(1_000L)
        assertEquals(90f, h.vista<View>(atividade, R.id.rotor).rotation)
        val teclado = h.vista<android.widget.GridLayout>(atividade, R.id.tecladoProvisionamento)
        fun texto(v: View?) = (v as? TextView)?.text?.toString()
        val um = teclado.getChildAt(0)

        assertEquals("2", texto(um.focusSearch(View.FOCUS_RIGHT)))
        assertEquals(texto(teclado.getChildAt(teclado.columnCount)), texto(um.focusSearch(View.FOCUS_DOWN)))
        assertEquals("CONECTAR", texto(um.focusSearch(View.FOCUS_UP)))
    }

    @Test
    fun `instalar pelo controle remoto leva direto a playlist`() {
        h.servidor.rotas["/player/provisionar"] =
            ServidorDeTeste.Resposta(200, """{"dispositivoId":"M-0235","chaveAparelho":"$chave"}""")
        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(200, h.playlistComUmVideo())
        val atividade = h.subir().get()

        "235".forEach { h.tecla(atividade, R.id.tecladoProvisionamento, it.toString()) }
        h.vista<TextView>(atividade, R.id.campoCodigo).performClick()
        "7K4M9Q2W".forEach { h.tecla(atividade, R.id.tecladoProvisionamento, it.toString()) }
        assertEquals("M-235", h.vista<TextView>(atividade, R.id.campoId).text.toString())
        assertEquals("7K4M-9Q2W", h.vista<TextView>(atividade, R.id.campoCodigo).text.toString())
        h.vista<TextView>(atividade, R.id.botaoConectar).performClick()

        h.esperar { h.servidor.contar("/playlist/M-0235") > 0 }
        val corpo = JSONObject(h.servidor.ultima("/player/provisionar")!!.corpo)
        assertEquals("M-0235", corpo.getString("codigoTela"))
        assertEquals("7K4M-9Q2W", corpo.getString("codigoInstalacao"))
        assertEquals(chave, h.servidor.ultima("/playlist/")!!.cabecalhos["x-aparelho-key"])
        assertEquals(View.GONE, h.vista<View>(atividade, R.id.telaProvisionamento).visibility)
        assertTrue(ConfigAparelho(h.contexto).provisionado)
    }

    @Test
    fun `codigo recusado mostra a mensagem e nao tenta de novo sozinho`() {
        h.servidor.rotas["/player/provisionar"] =
            ServidorDeTeste.Resposta(401, """{"erro":"ID da tela ou código de instalação inválido"}""")
        val atividade = h.subir().get()
        "235".forEach { h.tecla(atividade, R.id.tecladoProvisionamento, it.toString()) }
        h.vista<TextView>(atividade, R.id.campoCodigo).performClick()
        "7K4M9Q2W".forEach { h.tecla(atividade, R.id.tecladoProvisionamento, it.toString()) }

        h.vista<TextView>(atividade, R.id.botaoConectar).performClick()
        val mensagem = h.vista<TextView>(atividade, R.id.mensagemProvisionamento)
        h.esperar { mensagem.text.contains("inválido, expirado ou já usado") }
        h.avancar(10 * 60_000L)

        assertEquals(1, h.servidor.contar("/player/provisionar"))
        assertEquals("código recusado não fica na tela", "", h.vista<TextView>(atividade, R.id.campoCodigo).text.toString())
        assertEquals("M-235", h.vista<TextView>(atividade, R.id.campoId).text.toString())
        assertFalse(ConfigAparelho(h.contexto).provisionado)
    }

    @Test
    fun `401 numa rota autenticada volta para a instalacao com o ID preenchido`() {
        h.provisionar(id = "M-0042", chave = "revogada")
        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(401, """{"erro":"aparelho não autorizado"}""")
        h.servidor.rotas["/player/"] = ServidorDeTeste.Resposta(401, """{"erro":"aparelho não autorizado"}""")

        val atividade = h.subir().get()
        val tela = h.vista<View>(atividade, R.id.telaProvisionamento)
        h.esperar { tela.visibility == View.VISIBLE }

        val config = ConfigAparelho(h.contexto)
        assertNull(config.chaveAparelho)
        assertEquals("M-0042", config.dispositivoId)
        assertEquals("M-0042", h.vista<TextView>(atividade, R.id.campoId).text.toString())
        assertEquals("", h.vista<TextView>(atividade, R.id.campoCodigo).text.toString())
    }

    @Test
    fun `reinstalar depois de um 401 mostra o carregando e nao informa NOT_PROVISIONED`() {
        h.pularIntroducao()
        h.provisionar(id = "M-0042", chave = "revogada")
        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(401, """{"erro":"aparelho não autorizado"}""")
        h.servidor.rotas["/player/"] = ServidorDeTeste.Resposta(401, """{"erro":"aparelho não autorizado"}""")
        val atividade = h.subir().get()
        val tela = h.vista<View>(atividade, R.id.telaProvisionamento)
        h.esperar { tela.visibility == View.VISIBLE }

        // Reinstalação: a playlist demora (servidor lento) — a tela não pode
        // ficar preta, e o primeiro sinal já é de uma tela instalada.
        h.servidor.rotas["/player/provisionar"] =
            ServidorDeTeste.Resposta(200, """{"dispositivoId":"M-0042","chaveAparelho":"$chave"}""")
        h.servidor.rotas["/player/"] = ServidorDeTeste.Resposta(200, """{"configVersion":0,"playlist":{"atualizar":false}}""")
        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(pendurar = true)
        h.vista<TextView>(atividade, R.id.campoCodigo).performClick()
        "7K4M9Q2W".forEach { h.tecla(atividade, R.id.tecladoProvisionamento, it.toString()) }
        // O heartbeat recusado do ciclo anterior também conta: espera um novo.
        val antes = h.servidor.contar("/player/M-0042/heartbeat")
        h.vista<TextView>(atividade, R.id.botaoConectar).performClick()
        h.esperar { h.servidor.contar("/player/M-0042/heartbeat") > antes }

        val institucional = h.vista<br.com.mostrai.player.ui.TelaInstitucional>(atividade, R.id.institucional)
        assertEquals(View.VISIBLE, institucional.visibility)
        assertEquals(br.com.mostrai.player.ui.EstadoInstitucional.CARREGANDO, institucional.estado)
        val primeiro = JSONObject(h.servidor.ultima("/player/M-0042/heartbeat")!!.corpo)
        assertTrue("estado ${primeiro.getString("estado")}", primeiro.getString("estado") != "NOT_PROVISIONED")
    }

    @Test
    fun `falha passageira nao apaga a credencial`() {
        h.provisionar()
        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(503, "")
        h.servidor.rotas["/player/"] = ServidorDeTeste.Resposta(502, "")

        val atividade = h.subir().get()
        h.esperar { h.servidor.contar("/player/M-0001/heartbeat") > 0 && h.servidor.contar("/playlist/") > 0 }

        assertTrue(ConfigAparelho(h.contexto).provisionado)
        assertEquals(View.GONE, h.vista<View>(atividade, R.id.telaProvisionamento).visibility)
    }

    @Test
    fun `reinstalar como outra tela descarta a playlist guardada da anterior`() {
        h.provisionar(id = "M-0001", chave = "revogada")
        ConfigAparelho(h.contexto).esquecerCredencialSeFor("revogada")
        br.com.mostrai.player.playlist.PlaylistCache(h.contexto).salvar(h.playlistComUmVideo(), null)
        h.servidor.rotas["/player/provisionar"] =
            ServidorDeTeste.Resposta(200, """{"dispositivoId":"M-0002","chaveAparelho":"$chave"}""")
        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(503, "")
        val atividade = h.subir().get()

        h.vista<TextView>(atividade, R.id.campoId).performClick()
        h.tecla(atividade, R.id.tecladoProvisionamento, "⌫")
        h.tecla(atividade, R.id.tecladoProvisionamento, "2")
        h.vista<TextView>(atividade, R.id.campoCodigo).performClick()
        "7K4M9Q2W".forEach { h.tecla(atividade, R.id.tecladoProvisionamento, it.toString()) }
        h.vista<TextView>(atividade, R.id.botaoConectar).performClick()

        h.esperar { h.servidor.contar("/playlist/M-0002") > 0 }
        h.esperar { h.campo<Any>(atividade, "ultimaOrigemFetch").toString() == "NENHUMA" }
        assertNull(br.com.mostrai.player.playlist.PlaylistCache(h.contexto).carregar())
    }
}
