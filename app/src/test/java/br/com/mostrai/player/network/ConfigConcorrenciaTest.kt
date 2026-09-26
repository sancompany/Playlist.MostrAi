package br.com.mostrai.player.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import br.com.mostrai.player.config.ConfigAparelho
import br.com.mostrai.player.config.ConfigRemota
import br.com.mostrai.player.config.ConfigRemotaJson
import br.com.mostrai.player.estado.DiarioBordo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Config aplicada de forma serializada (contrato §6; BUG-010). */
@RunWith(RobolectricTestRunner::class)
class ConfigConcorrenciaTest {

    /**
     * `GET /config` devolve sempre a config ATUAL do servidor. A primeira
     * chamada fica presa e devolve o que era atual quando ela saiu (v12); a
     * segunda sai depois que o admin salvou a v13.
     */
    private class ApiDeConfig(config: ConfigAparelho) : MostraiApi(config) {
        val primeiraLiberada = CountDownLatch(1)
        val primeiraEntrou = CountDownLatch(1)
        private val chamadas = AtomicInteger()

        override fun buscarConfig(): ResultadoHttp<Pair<ConfigRemota, String>> {
            return if (chamadas.incrementAndGet() == 1) {
                primeiraEntrou.countDown()
                primeiraLiberada.await(10, TimeUnit.SECONDS)
                corpo(12, 1.0)
            } else {
                corpo(13, 4.0)
            }
        }

        private fun corpo(versao: Int, margem: Double): ResultadoHttp<Pair<ConfigRemota, String>> {
            val texto = """{"configVersion": $versao, "margens": {"superior": $margem}}"""
            return ResultadoHttp.Ok(ConfigRemotaJson.parse(texto)!! to texto)
        }
    }

    private lateinit var contexto: Context
    private lateinit var config: ConfigAparelho

    @Before
    fun preparar() {
        contexto = ApplicationProvider.getApplicationContext()
        contexto.getSharedPreferences(ConfigAparelho.ARQUIVO, Context.MODE_PRIVATE).edit().clear().commit()
        contexto.deleteDatabase(DiarioBordo.NOME_ARQUIVO)
        config = ConfigAparelho(contexto).apply { gravarCredenciais("M-0001", "chave") }
    }

    @Test
    fun `resposta atrasada de config antiga nao sobrescreve a nova`() {
        val api = ApiDeConfig(config)
        val sync = Sincronizacao(config, api, DiarioBordo(contexto))

        val primeira = thread { sync.sincronizarConfigSeNecessario(12) }
        api.primeiraEntrou.await(5, TimeUnit.SECONDS)
        val segunda = thread { sync.sincronizarConfigSeNecessario(13) }
        Thread.sleep(200)
        api.primeiraLiberada.countDown()
        primeira.join(5_000)
        segunda.join(5_000)

        assertEquals("config regrediu para uma versão antiga", 13, config.configVersionAplicada)
        assertEquals(4f, config.margens.topo)
    }
}
