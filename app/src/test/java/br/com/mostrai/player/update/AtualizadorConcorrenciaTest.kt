package br.com.mostrai.player.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import br.com.mostrai.player.BuildConfig
import br.com.mostrai.player.cache.ServidorDeTeste
import br.com.mostrai.player.estado.DiarioBordo
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Ciclo 2 — o atualizador sob manifestos e downloads simultâneos. */
@RunWith(RobolectricTestRunner::class)
class AtualizadorConcorrenciaTest {

    private lateinit var contexto: Context
    private lateinit var servidor: ServidorDeTeste
    private lateinit var diario: DiarioBordo
    private lateinit var atualizador: Atualizador

    private fun manifesto(build: Int, caminho: String, sha: String = "a".repeat(64)) = UpdateManifesto(
        disponivel = true, obrigatorio = false, versao = "x", build = build,
        url = "${servidor.baseUrl}$caminho", sha256 = sha, tamanhoBytes = null,
    )

    @Before
    fun preparar() {
        contexto = ApplicationProvider.getApplicationContext()
        contexto.getSharedPreferences(Atualizador.ARQUIVO_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        contexto.deleteDatabase(DiarioBordo.NOME_ARQUIVO)
        servidor = ServidorDeTeste()
        diario = DiarioBordo(contexto)
        atualizador = Atualizador(contexto, diario)
    }

    @After
    fun encerrar() = servidor.encerrar()

    @Test
    fun `download lento nao bloqueia o heartbeat de considerar outro manifesto`() {
        // baixarSeNecessario roda dentro do ciclo de heartbeat. Se ele segurar
        // o monitor durante o download, o heartbeat seguinte trava em
        // considerar() por minutos — e com ele os efeitos daquele heartbeat
        // (playlist.atualizar, margens) ficam parados.
        val trava = CountDownLatch(1)
        servidor.travas["/v1.apk"] = trava
        servidor.rotas["/v1.apk"] = ServidorDeTeste.Resposta(corpo = "apk".toByteArray())
        val m1 = manifesto(BuildConfig.VERSION_CODE + 1, "/v1.apk")
        atualizador.considerar(m1)
        val download = thread { atualizador.baixarSeNecessario(m1) }
        while (servidor.contar("/v1.apk") == 0) Thread.sleep(10)

        val inicio = System.nanoTime()
        atualizador.considerar(manifesto(BuildConfig.VERSION_CODE + 2, "/v2.apk"))
        val esperouMs = (System.nanoTime() - inicio) / 1_000_000

        trava.countDown()
        download.join(5_000)
        assertTrue("considerar esperou ${esperouMs}ms pelo download", esperouMs < 1_000)
    }

    @Test
    fun `download que falhou nao e repetido a cada heartbeat`() {
        // Um APK cujo hash não confere continua não conferindo. Voltar para
        // AVAILABLE no heartbeat seguinte rebaixa o APK inteiro a cada 5
        // minutos — 30 MB viram ~8,6 GB/dia na internet da loja.
        servidor.rotas["/ruim.apk"] = ServidorDeTeste.Resposta(corpo = "nao-confere".toByteArray())
        val m = manifesto(BuildConfig.VERSION_CODE + 1, "/ruim.apk")

        repeat(4) {
            atualizador.considerar(m)
            atualizador.baixarSeNecessario(m)
        }

        assertEquals(EstadoUpdate.FAILED, atualizador.estado)
        assertEquals("APK rejeitado foi rebaixado a cada ciclo", 1, servidor.contar("/ruim.apk"))
    }

    @Test
    fun `apk com sha256 divergente e recusado pelo hash, antes de qualquer outra checagem`() {
        // Sem esta asserção a conferência de pacote recusava o corpo de teste
        // por acaso e a do hash podia sumir sem ninguém notar (mutação M05).
        servidor.rotas["/ruim.apk"] = ServidorDeTeste.Resposta(corpo = "nao-confere".toByteArray())
        val m = manifesto(BuildConfig.VERSION_CODE + 1, "/ruim.apk")

        atualizador.considerar(m)
        atualizador.baixarSeNecessario(m)

        val falha = diario.ultimos(10).first { it.codigo == DiarioBordo.Codigo.UPDATE_FALHOU.name }
        assertTrue("motivo: ${falha.mensagem}", falha.mensagem!!.contains("SHA-256"))
        assertEquals(0, java.io.File(contexto.cacheDir, "update").listFiles()!!.count { it.name.endsWith(".apk") })
    }

    @Test
    fun `heartbeat que traz manifesto nao segura os proprios efeitos durante o download`() {
        // Auditoria D: o download saiu da trava (BUG-012), mas continuava
        // dentro de heartbeat(). O heartbeat que trazia o manifesto só
        // devolvia playlist.atualizar, margens e rotação depois do APK
        // inteiro — e o backend manda atualizar: true uma vez só.
        val trava = CountDownLatch(1)
        servidor.travas["/lento.apk"] = trava
        servidor.rotas["/lento.apk"] = ServidorDeTeste.Resposta(corpo = "apk".toByteArray())
        val m = manifesto(BuildConfig.VERSION_CODE + 1, "/lento.apk")
        val config = br.com.mostrai.player.config.ConfigAparelho(contexto).apply {
            baseUrl = servidor.baseUrl; dispositivoId = "tela-1"; chaveAparelho = "chave"
        }
        val api = object : br.com.mostrai.player.network.MostraiApi(config) {
            override fun heartbeat(corpo: br.com.mostrai.player.network.HeartbeatJson.Corpo) =
                br.com.mostrai.player.network.ResultadoHttp.Ok(
                    br.com.mostrai.player.network.HeartbeatJson.Resposta(atualizarPlaylist = true, update = m),
                )
        }
        val sync = br.com.mostrai.player.network.SincronizacaoV2(config, api, diario, atualizador)
        val corpo = br.com.mostrai.player.network.HeartbeatJson.Corpo(
            estado = br.com.mostrai.player.estado.EstadoPlayer.PLAYING, configVersionAplicada = 0,
            criativoId = null, ultimaPlaylistOkEm = null, filaPendentes = 0, filaMaisAntigoEm = null,
            erroCodigo = null, erroEm = null, erroMensagem = null, desvioRelogioMs = null, updateEstado = null,
        )

        val inicio = System.nanoTime()
        val efeitos = sync.heartbeat(corpo)
        val esperouMs = (System.nanoTime() - inicio) / 1_000_000
        trava.countDown()

        assertTrue(efeitos.atualizarPlaylist)
        assertTrue("heartbeat esperou ${esperouMs}ms pelo download do APK", esperouMs < 2_000)
    }
}
