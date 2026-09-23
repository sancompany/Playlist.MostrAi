package br.com.mostrai.player.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import br.com.mostrai.player.BuildConfig
import br.com.mostrai.player.cache.ServidorDeTeste
import br.com.mostrai.player.estado.DiarioBordo
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Ciclo 3 — o estado do OTA sobrevive ao processo; o download e o diálogo,
 * não. Estes testes escrevem o estado como ele fica em disco depois de um
 * reinício e olham o que o próximo heartbeat faz com ele.
 */
@RunWith(RobolectricTestRunner::class)
class AtualizadorPersistenciaTest {

    private lateinit var contexto: Context
    private lateinit var servidor: ServidorDeTeste
    private lateinit var diario: DiarioBordo
    private val build = BuildConfig.VERSION_CODE + 1

    private val apk = "apk".toByteArray()
    private val manifesto by lazy {
        UpdateManifesto(
            disponivel = true, obrigatorio = false, versao = "x", build = build,
            url = "${servidor.baseUrl}/v.apk",
            sha256 = br.com.mostrai.player.cache.ChaveCache.paraHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(apk),
            ),
            tamanhoBytes = null,
        )
    }

    @Before
    fun preparar() {
        contexto = ApplicationProvider.getApplicationContext()
        contexto.deleteDatabase(DiarioBordo.NOME_ARQUIVO)
        File(contexto.cacheDir, "update").deleteRecursively()
        servidor = ServidorDeTeste()
        servidor.rotas["/v.apk"] = ServidorDeTeste.Resposta(corpo = apk)
        diario = DiarioBordo(contexto)
    }

    @After
    fun encerrar() = servidor.encerrar()

    /** O que estava em disco quando o processo anterior morreu. */
    private fun estadoEmDisco(estado: EstadoUpdate, comApk: Boolean, proximaTentativaMs: Long = 0L) {
        contexto.getSharedPreferences(Atualizador.ARQUIVO_PREFS, Context.MODE_PRIVATE).edit().clear()
            .putString("estado", estado.name)
            .putInt("build_alvo", build)
            .putString("versao_alvo", "x")
            .putLong("proxima_tentativa_ms", proximaTentativaMs)
            .commit()
        if (comApk) File(contexto.cacheDir, "update").apply { mkdirs() }.resolve("$build.apk").writeBytes(apk)
    }

    private fun heartbeat(atualizador: Atualizador) {
        atualizador.reavaliarAdiamento()
        atualizador.considerar(manifesto)
        atualizador.baixarSeNecessario(manifesto)
    }

    @Test
    fun `download interrompido por reinicio e retomado`() {
        // O processo morreu com o APK pela metade (queda de energia no fim
        // do expediente, watchdog). DOWNLOADING ficou gravado; ninguém está
        // baixando. Sem retomar, esta tela nunca mais atualiza para este
        // build — o admin mostra "baixando" para sempre.
        estadoEmDisco(EstadoUpdate.DOWNLOADING, comApk = false)
        val atualizador = Atualizador(contexto, diario)

        heartbeat(atualizador)

        // O corpo de teste não é um APK de verdade, então o fim é FAILED na
        // conferência de pacote; o que importa é que o download foi retomado.
        assertEquals("download não foi retomado", 1, servidor.contar("/v.apk"))
        assertEquals(EstadoUpdate.FAILED, atualizador.estado)
    }

    @Test
    fun `instalacao ja pedida nao rebaixa o apk nem reabre o dialogo no heartbeat seguinte`() {
        // Diálogo aberto, operador longe da TV. O heartbeat de 5 min não pode
        // tratar o mesmo build como novidade: rebaixaria 30 MB e empilharia
        // outro diálogo de instalação a cada ciclo.
        estadoEmDisco(EstadoUpdate.INSTALL_REQUESTED, comApk = true, proximaTentativaMs = Long.MAX_VALUE)
        val atualizador = Atualizador(contexto, diario)

        repeat(3) { heartbeat(atualizador) }

        assertEquals(0, servidor.contar("/v.apk"))
        assertEquals(false, atualizador.podePedirInstalacao())
    }

    @Test
    fun `instalacao adiada pelo operador nao rebaixa o apk`() {
        estadoEmDisco(EstadoUpdate.DEFERRED, comApk = true, proximaTentativaMs = Long.MAX_VALUE)
        val atualizador = Atualizador(contexto, diario)

        repeat(3) { heartbeat(atualizador) }

        assertEquals(0, servidor.contar("/v.apk"))
        assertEquals(EstadoUpdate.DEFERRED, atualizador.estado)
    }

    @Test
    fun `instalacao pedida sem resposta volta a ser oferecida depois da espera, sem rebaixar`() {
        // Diálogo abandonado sem callback (TV desligada com ele aberto): sem
        // prazo, INSTALL_REQUESTED seria um beco sem saída.
        estadoEmDisco(EstadoUpdate.INSTALL_REQUESTED, comApk = true, proximaTentativaMs = 1L)
        val atualizador = Atualizador(contexto, diario)

        heartbeat(atualizador)

        assertEquals(0, servidor.contar("/v.apk"))
        assertEquals(true, atualizador.podePedirInstalacao())
    }

    @Test
    fun `heartbeat 200 com corpo ilegivel nao apaga a atualizacao pronta`() {
        // Auditoria F: corpo que não é JSON (proxy, página de erro servida
        // com 200) virava Resposta() vazia — a mesma coisa que "sem
        // atualização" — e considerar(null) apagava o APK já baixado e
        // verificado, ou zerava o adiamento que o operador tinha escolhido.
        estadoEmDisco(EstadoUpdate.READY, comApk = true)
        servidor.rotas["/player"] = ServidorDeTeste.Resposta(corpo = "<html>erro</html>".toByteArray())
        val config = br.com.mostrai.player.config.ConfigAparelho(contexto).apply {
            baseUrl = servidor.baseUrl; dispositivoId = "tela-1"; chaveAparelho = "chave"
        }
        val atualizador = Atualizador(contexto, diario)
        val sync = br.com.mostrai.player.network.SincronizacaoV2(
            config, br.com.mostrai.player.network.MostraiApi(config), diario, atualizador,
        )

        sync.heartbeat(
            br.com.mostrai.player.network.HeartbeatJson.Corpo(
                estado = br.com.mostrai.player.estado.EstadoPlayer.PLAYING, configVersionAplicada = 0,
                criativoId = null, ultimaPlaylistOkEm = null, filaPendentes = 0, filaMaisAntigoEm = null,
                erroCodigo = null, erroEm = null, erroMensagem = null, desvioRelogioMs = null, updateEstado = null,
            ),
        )

        assertEquals(EstadoUpdate.READY, atualizador.estado)
    }
}
