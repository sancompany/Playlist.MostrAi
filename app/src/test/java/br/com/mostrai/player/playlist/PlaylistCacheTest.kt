package br.com.mostrai.player.playlist

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Ciclo 3 — a âncora de relógio atravessando um reboot. */
@RunWith(RobolectricTestRunner::class)
class PlaylistCacheTest {

    private lateinit var contexto: Context

    private fun definirBoot(n: Int) {
        Settings.Global.putInt(contexto.contentResolver, Settings.Global.BOOT_COUNT, n)
    }

    @Before
    fun preparar() {
        contexto = ApplicationProvider.getApplicationContext()
        contexto.getSharedPreferences("mostrai_cache_playlist", Context.MODE_PRIVATE).edit().clear().commit()
        definirBoot(5)
    }

    @Test
    fun `ancora do boot anterior nao vale depois que o uptime passa o dela`() {
        // A TV da loja desliga toda noite. A âncora de ontem foi salva com
        // uptime X; hoje, depois de X de uptime, "elapsedRealtime >= X" volta
        // a ser verdade e ela parecia válida — projetando o horário do
        // servidor a partir de ontem, sem contar a noite desligada.
        val ancora = RelogioJanela(servidorAgoraEpochMs = 1_000_000L, elapsedRealtimeNaAncoraMs = SystemClock.elapsedRealtime())
        PlaylistCache(contexto).salvar("{}", ancora)

        definirBoot(6)

        val salva = PlaylistCache(contexto).carregar()
        assertNotNull(salva)
        assertNull("âncora de outro boot foi aceita", salva!!.ancora)
    }

    @Test
    fun `ancora do mesmo boot continua valendo`() {
        val ancora = RelogioJanela(servidorAgoraEpochMs = 1_000_000L, elapsedRealtimeNaAncoraMs = SystemClock.elapsedRealtime())
        PlaylistCache(contexto).salvar("{}", ancora)

        assertEquals(ancora, PlaylistCache(contexto).carregar()!!.ancora)
    }
}
