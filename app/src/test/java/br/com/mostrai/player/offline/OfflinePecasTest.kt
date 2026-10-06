package br.com.mostrai.player.offline

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
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

    @get:org.junit.Rule
    val disco = br.com.mostrai.player.cache.DiscoFolgado()

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
            cache.espacoLivre = { 50L shl 30 }
            cache.espacoTotal = { 100L shl 30 }
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

    private fun registroLimpo(): RegistroOperacional {
        contexto.deleteDatabase(RegistroOperacional.NOME_ARQUIVO)
        contexto.getSharedPreferences(RegistroOperacional.ARQUIVO_BOOT, Context.MODE_PRIVATE).edit().clear().commit()
        return RegistroOperacional(contexto)
    }

    private fun bootCount(n: Int) =
        Settings.Global.putInt(contexto.contentResolver, Settings.Global.BOOT_COUNT, n)

    private fun minutos(n: Long) = ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(n))

    @Test
    fun `segmento dura pelo relogio monotonico e sobrevive ao reinicio do app`() {
        bootCount(5)
        val registro = registroLimpo()
        registro.ancorar(servidorAgoraMs = 1_000_000_000L)
        registro.abrir("M-0235")
        minutos(90)
        registro.estender()

        // Processo morto: outra instância, mesma base, mesmo boot.
        val s = RegistroOperacional(contexto).pendentes("M-0235").single()
        assertEquals(90 * 60_000L, s.duracaoMs)
        assertTrue(s.aberto)
        assertEquals(s.duracaoMs, s.fimServidorMs!! - s.inicioServidorMs!!)
        assertTrue(s.bootId.startsWith("b5."))
    }

    @Test
    fun `sem ancora neste boot o segmento espera, e vai no relogio do servidor quando ela chega`() {
        bootCount(5)
        val registro = registroLimpo()
        registro.abrir("M-0235")
        minutos(10)
        registro.estender()
        assertTrue("sem âncora não há como pôr no relógio do servidor", registro.pendentes("M-0235").isEmpty())

        registro.ancorar(servidorAgoraMs = 50_000_000L)
        val s = registro.pendentes("M-0235").single()
        assertEquals("início recalculado para trás pela âncora", 50_000_000L - 10 * 60_000L, s.inicioServidorMs)
        assertEquals(50_000_000L, s.fimServidorMs)
    }

    @Test
    fun `boot que terminou sem nunca falar com o servidor e descartado, e o descarte e contado`() {
        bootCount(5)
        val registro = registroLimpo()
        registro.abrir("M-0235")
        minutos(40)
        registro.fechar()

        bootCount(6) // reboot sem rede
        val descarte = registro.arrumar("M-0235")

        assertEquals(1, descarte.segmentos)
        assertEquals(40 * 60_000L, descarte.duracaoMs)
        assertTrue(registro.pendentes("M-0235").isEmpty())
        assertEquals(0, registro.resumoPendente().first)
    }

    @Test
    fun `queda de energia fecha no ultimo checkpoint, sem inventar tempo, e o novo boot abre outro seq`() {
        bootCount(5)
        val registro = registroLimpo()
        registro.ancorar(1_000_000_000L)
        registro.abrir("M-0235")
        minutos(30)
        registro.estender()
        minutos(45) // sem checkpoint: a TV apagou

        bootCount(6)
        registro.arrumar("M-0235")
        registro.ancorar(2_000_000_000L)
        registro.abrir("M-0235")
        minutos(1)
        registro.estender()

        val segmentos = registro.pendentes("M-0235")
        assertEquals(2, segmentos.size)
        val antigo = segmentos.first { it.bootId.startsWith("b5.") }
        assertEquals("contou tempo depois do último checkpoint", 30 * 60_000L, antigo.duracaoMs)
        assertFalse(antigo.aberto)
        assertTrue(segmentos.first { it.bootId.startsWith("b6.") }.aberto)
    }

    @Test
    fun `segmento passa de 6 h e rola para o proximo seq, nenhum acima do teto do contrato`() {
        bootCount(5)
        val registro = registroLimpo()
        registro.ancorar(1_000_000_000L)
        registro.abrir("M-0235")
        minutos(13 * 60)
        registro.estender()

        val segmentos = registro.pendentes("M-0235")
        assertEquals(listOf(0, 1, 2), segmentos.map { it.seq })
        assertTrue(segmentos.all { it.duracaoMs <= 6 * 60 * 60_000L })
        assertEquals(13 * 60 * 60_000L, segmentos.sumOf { it.duracaoMs })
        assertEquals("contínuos, sem buraco", segmentos[0].fimUptimeMs, segmentos[1].inicioUptimeMs)
    }

    @Test
    fun `ok confirma o aberto ate onde foi, e o que cresceu depois volta, fechado sai`() {
        bootCount(5)
        val registro = registroLimpo()
        registro.ancorar(1_000_000_000L)
        registro.abrir("M-0235")
        minutos(10)
        registro.estender()
        val enviados = registro.pendentes("M-0235")
        val ok = enviados.associate { (it.bootId to it.seq) to RegistroOperacional.STATUS_OK }

        registro.confirmar(enviados, ok)
        registro.confirmar(enviados, ok) // retentativa: nada muda
        assertTrue("aberto confirmado continuou pendente", registro.pendentes("M-0235").isEmpty())

        minutos(5)
        registro.fechar()
        val resto = registro.pendentes("M-0235").single()
        assertEquals(15 * 60_000L, resto.duracaoMs)
        assertFalse(resto.aberto)

        registro.confirmar(listOf(resto), mapOf((resto.bootId to resto.seq) to RegistroOperacional.STATUS_OK))
        assertTrue(registro.pendentes("M-0235").isEmpty())
        assertEquals(0, registro.resumoPendente().first)
    }

    @Test
    fun `item_invalido e ignorado sao finais, e um seq nunca se repete no mesmo boot`() {
        bootCount(5)
        val registro = registroLimpo()
        registro.ancorar(1_000_000_000L)
        registro.abrir("M-0235"); minutos(5); registro.fechar()
        registro.abrir("M-0235"); minutos(5); registro.fechar()
        val enviados = registro.pendentes("M-0235")
        registro.confirmar(
            enviados,
            mapOf(
                (enviados[0].bootId to enviados[0].seq) to RegistroOperacional.STATUS_INVALIDO,
                (enviados[1].bootId to enviados[1].seq) to RegistroOperacional.STATUS_IGNORADO,
            ),
        )
        assertTrue(registro.pendentes("M-0235").isEmpty())

        registro.abrir("M-0235"); minutos(5); registro.fechar()
        assertEquals("seq reaproveitado mesclaria no servidor", 2, registro.pendentes("M-0235").single().seq)
    }

    @Test
    fun `reinstalado como outra tela, segmentos da anterior nao vao com a credencial nova`() {
        bootCount(5)
        val registro = registroLimpo()
        registro.ancorar(1_000_000_000L)
        registro.abrir("M-0235"); minutos(5); registro.fechar()

        registro.arrumar("M-0999")

        assertTrue(registro.pendentes("M-0999").isEmpty())
        assertTrue(registro.pendentes("M-0235").isEmpty())
    }

    @Test
    fun `corpo e o do contrato 8_5, fatos sem conclusao de negocio`() {
        bootCount(5)
        val registro = registroLimpo()
        registro.ancorar(Instant.parse("2026-10-05T14:00:00Z").toEpochMilli())
        registro.abrir("M-0235"); minutos(30); registro.fechar()

        val s = registro.pendentes("M-0235").single()
        val corpo = JSONObject(OperacaoJson.corpo(listOf(s)))
        val seg = corpo.getJSONArray("segmentos").getJSONObject(0)

        assertEquals(setOf("bootId", "seq", "inicio", "fim"), seg.keys().asSequence().toSet())
        assertEquals("2026-10-05T14:00:00Z", seg.getString("inicio"))
        assertEquals("2026-10-05T14:30:00Z", seg.getString("fim"))
        assertTrue(Regex("^[A-Za-z0-9._:-]{1,64}$").matches(seg.getString("bootId")))
        assertEquals(
            mapOf((s.bootId to 0) to "ok"),
            OperacaoJson.parseResultados("""{"resultados":[{"bootId":"${s.bootId}","seq":0,"status":"ok"}]}"""),
        )
        assertNull(OperacaoJson.parseResultados("""{"erro":"x"}"""))
    }

    @After
    fun fim() = Unit
}
