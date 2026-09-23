package br.com.mostrai.player.network

import java.io.IOException
import org.junit.Assert.assertThrows
import org.junit.Test

/** Sem dependência de Android — roda no JVM puro. */
class HttpClienteTest {

    @Test
    fun `esquema que nao e http vira IOException, nunca ClassCastException`() {
        // openConnection() devolve uma FtpURLConnection para "ftp://", e o
        // cast pra HttpURLConnection falha — sem a guarda em HttpCliente,
        // isso escaparia como ClassCastException (não é IOException) e
        // derrubaria qualquer chamador que só espera IOException. A falha
        // acontece no cast, antes de qualquer E/S — não precisa de rede.
        val cliente = HttpCliente()
        assertThrows(IOException::class.java) {
            cliente.get("ftp://exemplo.invalido/arquivo", emptyMap())
        }
    }
}
