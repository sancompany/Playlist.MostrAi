package br.com.mostrai.player.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import br.com.mostrai.player.config.ConfigAparelho
import br.com.mostrai.player.config.ConfigRemotaJson
import br.com.mostrai.player.estado.DiarioBordo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Ciclo 8 — a janela de silêncio após cancelamento vem da config (contrato §5 e §8.3). */
@RunWith(RobolectricTestRunner::class)
class ReceptorInstalacaoTest {

    private lateinit var contexto: Context

    @Before
    fun preparar() {
        contexto = ApplicationProvider.getApplicationContext()
        contexto.getSharedPreferences("mostrai_config", Context.MODE_PRIVATE).edit().clear().commit()
        contexto.deleteDatabase(DiarioBordo.NOME_ARQUIVO)
        contexto.getSharedPreferences(Atualizador.ARQUIVO_PREFS, Context.MODE_PRIVATE).edit().clear()
            .putString("estado", EstadoUpdate.INSTALL_REQUESTED.name)
            .putInt("build_alvo", 99)
            .commit()
        File(contexto.cacheDir, "update").apply { mkdirs() }.resolve("99.apk").writeBytes(byteArrayOf(1))
    }

    private fun proximaTentativaMs() =
        contexto.getSharedPreferences(Atualizador.ARQUIVO_PREFS, Context.MODE_PRIVATE).getLong("proxima_tentativa_ms", 0L)

    @Test
    fun `cancelamento respeita horasEntreTentativas da config`() {
        val corpo = """{"configVersion": 1, "update": {"horasEntreTentativas": 1}}"""
        ConfigAparelho(contexto).aplicarConfigRemota(ConfigRemotaJson.parse(corpo)!!, corpo)

        val antes = System.currentTimeMillis()
        ReceptorInstalacao().onReceive(
            contexto,
            Intent().putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED),
        )

        assertEquals(EstadoUpdate.DEFERRED, Atualizador(contexto, DiarioBordo(contexto)).estado)
        val espera = proximaTentativaMs() - antes
        assertTrue("espera de ${espera / 60_000} min, config pedia 60", espera in 59 * 60_000L..61 * 60_000L)
    }
}
