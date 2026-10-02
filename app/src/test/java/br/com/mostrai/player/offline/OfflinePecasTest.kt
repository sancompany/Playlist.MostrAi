package br.com.mostrai.player.offline

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import br.com.mostrai.player.PlayerActivity
import br.com.mostrai.player.cache.CacheMidia
import br.com.mostrai.player.cache.ChaveCache
import br.com.mostrai.player.cache.ServidorDeTeste
import br.com.mostrai.player.config.ConfigAparelho
import br.com.mostrai.player.config.FaixaHoraria
import br.com.mostrai.player.config.HorarioOperacional
import br.com.mostrai.player.network.OperacaoJson
import br.com.mostrai.player.operacao.RegistroOperacional
import br.com.mostrai.player.playlist.InstitucionalLocal
import br.com.mostrai.player.playlist.ItemPlaylist
import br.com.mostrai.player.playlist.Playlist
import br.com.mostrai.player.playlist.RelogioConfiavel
import br.com.mostrai.player.playlist.RelogioJanela
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock

/**
 * Ponto Móvel (02/10/2026): as peças do offline prolongado, uma a uma.
 * O ciclo da tela inteira está em `ciclo/PontoMovelCicloTest`.
 */
@RunWith(RobolectricTestRunner::class)
class OfflinePecasTest {

    private val contexto: Context = ApplicationProvider.getApplicationContext()
    private val hora = 3_600_000L

    @Before
    fun limpar() {
        listOf(RelogioConfiavel.ARQUIVO, "mostrai_institucional").forEach {
            contexto.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        contexto.deleteDatabase(RegistroOperacional.NOME_ARQUIVO)
        File(contexto.filesDir, "midia").deleteRecursively()
        File(contexto.cacheDir, "midia").deleteRecursively()
    }

    // ----------------------------------------------------- relógio confiável

    @Test
    fun `ancora do servidor vence o relogio da TV`() {
        assertEquals(1_000L, RelogioConfiavel.escolher(ancoraMs = 1_000L, paredeMs = 9_999_999L, pisoMs = 0L))
    }

    @Test
    fun `sem ancora, o relogio da TV so vale se nao estiver atras do piso`() {
        assertEquals(5_000L, RelogioConfiavel.escolher(null, paredeMs = 5_000L, pisoMs = 4_000L))
        // TV sem RTC voltou para a data do firmware: comprovadamente errado.
        assertNull(RelogioConfiavel.escolher(null, paredeMs = 3_000L, pisoMs = 4_000L))
        // Nunca falou com o servidor: não há como julgar.
        assertNull(RelogioConfiavel.escolher(null, paredeMs = 5_000L, pisoMs = 0L))
    }

    @Test
    fun `o piso so sobe, e so com ancora valida`() {
        val relogio = RelogioConfiavel(contexto)
        relogio.registrarPiso(RelogioJanela(servidorAgoraEpochMs = 10_000L, elapsedRealtimeNaAncoraMs = SystemClock.elapsedRealtime()))
        val piso = relogio.pisoMs()
        assertTrue(piso >= 10_000L)

        relogio.registrarPiso(RelogioJanela(servidorAgoraEpochMs = 1_000L, elapsedRealtimeNaAncoraMs = SystemClock.elapsedRealtime()))
        assertEquals("piso desceu", piso, relogio.pisoMs())

        relogio.registrarPiso(null)
        assertEquals(piso, relogio.pisoMs())
    }

    // ------------------------------------------------- validade da janela

    private fun playlist(inicio: Instant?, fim: Instant?, itens: List<ItemPlaylist> = listOf(comercial)) =
        Playlist("j", inicio?.toString(), null, itens, janelaFim = fim?.toString())

    private val comercial = ItemPlaylist("i1", "c1", 15, "https://x/c1.mp4", contabiliza = true)
    private val institucional = ItemPlaylist("i2", null, 30, "https://x/inst.mp4", contabiliza = false, institucional = true)

    @Test
    fun `comercial so toca antes do fim da janela autorizada`() {
        val inicio = Instant.parse("2026-10-05T14:00:00Z")
        val p = playlist(inicio, inicio.plusMillis(hora))

        assertTrue(p.comercialAutorizadoEm(inicio.plusMillis(30 * 60_000L).toEpochMilli()))
        assertFalse("repetiu comercial vencido", p.comercialAutorizadoEm(inicio.plusMillis(hora).toEpochMilli()))
        assertFalse("12 h depois, offline", p.comercialAutorizadoEm(inicio.plusMillis(12 * hora).toEpochMilli()))
    }

    @Test
    fun `sem hora confiavel ou sem fim conhecido, nada comercial toca`() {
        val inicio = Instant.parse("2026-10-05T14:00:00Z")
        assertFalse(playlist(inicio, inicio.plusMillis(hora)).comercialAutorizadoEm(null))
        assertFalse(playlist(null, null).comercialAutorizadoEm(inicio.toEpochMilli()))
    }

    @Test
    fun `sem janelaFim, vale uma hora desde o inicio`() {
        val inicio = Instant.parse("2026-10-05T14:00:00Z")
        assertEquals(inicio.plusMillis(hora).toEpochMilli(), playlist(inicio, null).validaAteMs())
    }

    @Test
    fun `fallback so tem institucional com midia`() {
        val semMidia = institucional.copy(url = null)
        val p = playlist(null, null, listOf(comercial, institucional, semMidia))
        assertEquals(listOf(institucional), p.institucionais())
    }

    // ---------------------------------------------------- cache institucional

    @Test
    fun `institucional fica guardado, sobrevive a reinicio e a hora toda vendida`() {
        InstitucionalLocal(contexto).atualizar(playlist(null, null, listOf(comercial, institucional)))
        // Hora toda vendida: nenhum institucional — não apaga o guardado.
        InstitucionalLocal(contexto).atualizar(playlist(null, null, listOf(comercial)))

        val guardado = InstitucionalLocal(contexto).itens()
        assertEquals(1, guardado.size)
        assertEquals("https://x/inst.mp4", guardado[0].url)
        assertFalse("fallback nunca vira comprovante", guardado[0].contabiliza)
    }

    // -------------------------------------------------------------- cache

    @Test
    fun `cache mora em filesDir e migra o que estava em cacheDir`() {
        val antigo = File(contexto.cacheDir, "midia").apply { mkdirs() }
        File(antigo, "sha256-${"a".repeat(64)}").writeText("video")

        val cache = CacheMidia(contexto)

        val item = comercial.copy(contentHash = "a".repeat(64))
        assertTrue("o Android apaga cacheDir sob pressão; um evento sem internet não rebaixa", cache.emCache(item))
        assertTrue(File(contexto.filesDir, "midia/sha256-${"a".repeat(64)}").exists())
    }

    @Test
    fun `com pouco espaco, sai o que nao e protegido e nunca o da programacao`() {
        val servidor = ServidorDeTeste().apply { corpo = "x".repeat(10).toByteArray() }
        try {
            val cache = CacheMidia(contexto)
            val protegido = comercial.copy(url = "${servidor.baseUrl}/a.mp4", criativoId = "a")
            val descartavel = comercial.copy(url = "${servidor.baseUrl}/b.mp4", criativoId = "b")
            assertNotNull(cache.resolver(protegido))
            assertNotNull(cache.resolver(descartavel))
            cache.proteger(listOf(protegido))

            // Disco abaixo da reserva: o próximo download precisa liberar espaço.
            cache.espacoLivre = { 0L }
            cache.espacoTotal = { 1_000_000L }
            val novo = cache.resolucao(comercial.copy(url = "${servidor.baseUrl}/c.mp4", criativoId = "c"))

            assertTrue("removeu mídia da programação em vigor", cache.emCache(protegido))
            assertFalse(cache.emCache(descartavel))
            assertEquals("sem espaço, falha em vez de apagar o protegido", CacheMidia.Falha.SemEspaco, novo.falha)
            assertEquals(1 to 2, cache.disponiveis(listOf(protegido, descartavel)))
        } finally {
            servidor.encerrar()
        }
    }

    // -------------------------------------------------------- horário / relógio

    @Test
    fun `horario usa o offset padrao, imune a tzdata com horario de verao`() {
        // A TCL sai com tzdata de 2017, que ainda aplica horário de verão ao
        // Brasil. Nova York tem horário de verão na tzdata atual: serve para
        // provar que a conta usa o offset padrão (−05:00), não o de verão.
        val faixa = listOf(FaixaHoraria(8 * 60, 9 * 60))
        val horario = HorarioOperacional("America/New_York", DayOfWeek.values().associateWith { faixa })
        // 12:30 UTC em julho = 07:30 padrão (fora) / 08:30 de verão (dentro).
        assertFalse(horario.estaDentro(Instant.parse("2026-07-15T12:30:00Z")))
        assertTrue(horario.estaDentro(Instant.parse("2026-07-15T13:30:00Z")))
    }

    @Test
    fun `virada de hora e calculada pelo relogio confiavel, nao pelo Calendar da TV`() {
        val agora = Instant.parse("2026-10-05T13:40:00Z").toEpochMilli()
        assertEquals(20 * 60_000L + 7_000L, PlayerActivity.atrasoAteViradaMs(agora, atrasoTelaSegundos = 7))
        assertEquals(1_000L, PlayerActivity.atrasoAteViradaMs(Instant.parse("2026-10-05T14:59:59.999Z").toEpochMilli(), 0))
    }

    @Test
    fun `bloqueio do PIN nunca passa do teto, mesmo com o relogio voltando dias`() {
        val config = ConfigAparelho(contexto)
        val adiantado = System.currentTimeMillis() + 2 * 24 * hora
        repeat(3) { config.registrarPinErrado(agoraMs = adiantado) }

        assertTrue(config.pinBloqueadoPorMs() <= ConfigAparelho.BLOQUEIO_PIN_MAXIMO_MS)
    }

    // ---------------------------------------------------- tempo operacional

    @Test
    fun `sessao operacional dura pelo relogio monotonico e sobrevive ao reinicio do app`() {
        val registro = RegistroOperacional(contexto)
        val id = registro.abrir("M-0235", servidorMs = 1_000L)!!
        ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(90))
        registro.checkpoint(id, servidorMs = null)

        // Processo morto: outra instância, mesma base.
        val depois = RegistroOperacional(contexto).pendentes()
        assertEquals(1, depois.size)
        assertEquals(90 * 60_000L, depois[0].duracaoMs)
        assertTrue(depois[0].aberta)
    }

    @Test
    fun `relogio do servidor anda com o monotonico, inicio recalculado e fim projetado`() {
        val registro = RegistroOperacional(contexto)
        // Abriu antes da primeira âncora: sem instante do servidor.
        val id = registro.abrir("M-0235", servidorMs = null)!!
        ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(10))
        // Veio a âncora: o servidor diz que agora é 1_000_000.
        registro.checkpoint(id, servidorMs = 1_000_000L)
        // A âncora se perdeu (403, sem playlist) e a sessão seguiu 5 min.
        ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(5))
        registro.checkpoint(id, servidorMs = null)

        val s = registro.pendentes().single()
        assertEquals("início projetado para trás", 1_000_000L - 10 * 60_000L, s.inicioServidorMs)
        assertEquals("fim não fica parado", 1_000_000L + 5 * 60_000L, s.fimServidorMs)
        assertEquals(s.duracaoMs, s.fimServidorMs!! - s.inicioServidorMs!!)
    }

    @Test
    fun `queda de energia encerra a sessao no ultimo checkpoint, sem inventar tempo`() {
        val registro = RegistroOperacional(contexto)
        val id = registro.abrir("M-0235", null)!!
        ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(30))
        registro.checkpoint(id, null)
        ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(45)) // sem checkpoint: a TV apagou

        val nova = registro.abrir("M-0235", null)!!

        val sessoes = registro.pendentes().associateBy { it.sessaoId }
        assertEquals(RegistroOperacional.MOTIVO_INTERROMPIDA, sessoes.getValue(id).motivoFim)
        assertEquals("contou tempo depois do último sinal", 30 * 60_000L, sessoes.getValue(id).duracaoMs)
        assertTrue(sessoes.getValue(nova).aberta)
    }

    @Test
    fun `confirmacao e idempotente e o que andou depois volta a ser enviado`() {
        val registro = RegistroOperacional(contexto)
        val id = registro.abrir("M-0235", null)!!
        ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(10))
        registro.checkpoint(id, null)
        val enviadas = registro.pendentes()

        registro.confirmar(enviadas, setOf(id))
        registro.confirmar(enviadas, setOf(id)) // retentativa: nada muda
        assertTrue("sessão confirmada continuou pendente", registro.pendentes().isEmpty())

        ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(5))
        registro.fechar(id, "parou", null)
        val resto = registro.pendentes()
        assertEquals(1, resto.size)
        assertEquals(15 * 60_000L, resto[0].duracaoMs)
        assertFalse(resto[0].aberta)

        registro.confirmar(resto, setOf(id))
        assertTrue(registro.pendentes().isEmpty())
    }

    @Test
    fun `corpo da sessao leva fatos, nunca conclusao de negocio`() {
        val registro = RegistroOperacional(contexto)
        val id = registro.abrir("M-0235", servidorMs = Instant.parse("2026-10-05T14:00:00Z").toEpochMilli())!!
        registro.fechar(id, "saida_pin", null)

        val sessao = JSONObject(OperacaoJson.corpo(registro.pendentes())).getJSONArray("sessoes").getJSONObject(0)

        assertEquals(id, sessao.getString("sessaoId"))
        assertEquals("2026-10-05T14:00:00Z", sessao.getString("inicioServidorEm"))
        assertTrue(sessao.getBoolean("encerrada"))
        assertEquals("saida_pin", sessao.getString("motivo"))
        assertFalse(sessao.has("hospedagemId"))
        assertEquals(setOf(id), OperacaoJson.parseConfirmadas("""{"resultados":[{"sessaoId":"$id","status":"registrada"}]}"""))
    }

    @After
    fun fim() = Unit
}
