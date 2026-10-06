package br.com.mostrai.player.ciclo

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.TextView
import br.com.mostrai.player.BuildConfig
import br.com.mostrai.player.PlayerActivity
import br.com.mostrai.player.R
import br.com.mostrai.player.atualizacao.AcessoUsb
import br.com.mostrai.player.atualizacao.ApkFalso
import br.com.mostrai.player.atualizacao.EstadoAtualizacao
import br.com.mostrai.player.atualizacao.FontesAtualizacao
import br.com.mostrai.player.atualizacao.FonteVolumes
import br.com.mostrai.player.atualizacao.InfoApk
import br.com.mostrai.player.atualizacao.ValidacaoAtualizacao
import br.com.mostrai.player.atualizacao.VolumeUsb
import br.com.mostrai.player.cache.ServidorDeTeste
import br.com.mostrai.player.estado.DiarioBordo
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * Atualização oficial por pendrive com o Player inteiro rodando: o pendrive
 * é percebido (aviso do Android, vigia de 30 s ou já presente ao abrir), o
 * vídeo segue enquanto copia e confere, o modal aparece uma vez, "Depois"
 * vale até o pendrive sair, e "Atualizar agora" entrega a cópia ao
 * instalador do Android.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AtualizacaoUsbCicloTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var h: Harness
    private lateinit var pendrive: File
    private lateinit var volume: VolumeUsb
    private val montados = CopyOnWriteArrayList<VolumeUsb>()

    @Volatile private var acesso: (VolumeUsb) -> AcessoUsb.Acesso = { AcessoUsb.Acesso.Direto(it.raiz!!) }

    /** Quantas vezes o Player foi ler um pendrive (cada leitura copia o APK). */
    private val leituras = java.util.concurrent.atomic.AtomicInteger()

    private val oficial = BuildConfig.CERTIFICADO_OFICIAL_SHA256
    private val atual = BuildConfig.VERSION_CODE.toLong()

    @Before
    fun preparar() {
        h = Harness()
        h.provisionar()
        h.aplicarConfig("""{"configVersion":0,"pinSaida":"4821"}""")
        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(200, h.playlistComUmVideo(duracao = 600))
        h.midiaNoAr()
        pendrive = tmp.newFolder("ABCD-1234")
        volume = VolumeUsb("ABCD-1234", pendrive)
        FontesAtualizacao.volumes = { FonteVolumes { montados.toList() } }
        FontesAtualizacao.leitor = { ApkFalso.LeitorFalso }
        FontesAtualizacao.instalado = { InfoApk(BuildConfig.APPLICATION_ID, atual, BuildConfig.VERSION_NAME, setOf(oficial)) }
        FontesAtualizacao.acesso = { _, v -> leituras.incrementAndGet(); acesso(v) }
        shadowOf(h.contexto.packageManager).setCanRequestPackageInstalls(true)
    }

    @After
    fun encerrar() = h.encerrar()

    // ------------------------------------------------------------ apoio

    private fun gravarPacote(versionCode: Long = atual + 1, versionName: String = "3.0.2", certificado: String = oficial) {
        val apk = ApkFalso.bytes(versionCode = versionCode, versionName = versionName, certificados = setOf(certificado))
        ApkFalso.gravarOficial(pendrive, apk, ApkFalso.json(apk, versionCode = versionCode, certificado = certificado))
    }

    private fun conectar(avisar: Boolean = true) {
        montados += volume
        if (avisar) h.contexto.sendBroadcast(Intent(Intent.ACTION_MEDIA_MOUNTED, Uri.fromFile(pendrive)))
    }

    private fun desconectar() {
        montados.clear()
        h.contexto.sendBroadcast(Intent(Intent.ACTION_MEDIA_UNMOUNTED, Uri.fromFile(pendrive)))
        h.esperar { EstadoAtualizacao.conectados.isEmpty() }
    }

    private fun modal(a: PlayerActivity) = h.vista<View>(a, R.id.telaAtualizacao)
    private fun modalVisivel(a: PlayerActivity) = modal(a).visibility == View.VISIBLE
    private fun texto(a: PlayerActivity, id: Int) = h.vista<TextView>(a, id).text.toString()

    private fun subirExibindo(): ActivityController<PlayerActivity> {
        val controle = h.subir()
        h.esperar { h.campo<String?>(controle.get(), "execucaoAtualId") != null }
        return controle
    }

    private fun esperarResultado(mensagem: String) = h.esperar { EstadoAtualizacao.ultimoResultado == mensagem }

    private fun recusas() = DiarioBordo(h.contexto).ultimos(50).filter { it.codigo == DiarioBordo.Codigo.ATUALIZACAO_RECUSADA.name }

    private fun abrirPainel(a: PlayerActivity) {
        h.voltar(a)
        assertEquals(View.VISIBLE, h.vista<View>(a, R.id.telaPin).visibility)
        h.esperar { texto(a, R.id.infoSuporte).contains("ATUALIZAÇÃO") }
    }

    // ------------------------------------------------------------ detecção

    @Test
    fun `pendrive ja presente ao abrir o Player oferece a atualizacao e o video segue`() {
        gravarPacote()
        conectar(avisar = false)
        val a = subirExibindo().get()
        val execucao = h.campo<String?>(a, "execucaoAtualId")

        h.esperar { modalVisivel(a) }

        assertEquals("Nova versão encontrada.", texto(a, R.id.mensagemAtualizacao))
        assertEquals("Atual: ${BuildConfig.VERSION_NAME}\nNova: 3.0.2", texto(a, R.id.versoesAtualizacao))
        assertEquals("Atualizar agora", texto(a, R.id.botaoAtualizacaoPrimario))
        assertEquals("Depois", texto(a, R.id.botaoAtualizacaoDepois))
        assertEquals("a exibição em andamento segue valendo", execucao, h.campo<String?>(a, "execucaoAtualId"))
        assertFalse(a.isFinishing)
    }

    @Test
    fun `pendrive conectado com o Player rodando e percebido pelo aviso do Android`() {
        gravarPacote()
        val a = subirExibindo().get()
        assertFalse(modalVisivel(a))

        conectar()

        // Sem avançar o relógio: não é o vigia de 30 s, é o broadcast.
        h.esperar { modalVisivel(a) }
    }

    @Test
    fun `vigia de 30 s acha o pendrive quando o Android nao avisa`() {
        gravarPacote()
        val a = subirExibindo().get()
        conectar(avisar = false)
        h.deixarRodar(300)
        assertFalse(modalVisivel(a))

        h.avancar(30_000)

        h.esperar { modalVisivel(a) }
    }

    // ------------------------------------------------------------ Depois

    @Test
    fun `Depois esconde e o modal nao se repete com o pendrive conectado`() {
        gravarPacote()
        val a = subirExibindo().get()
        conectar()
        h.esperar { modalVisivel(a) }

        h.vista<View>(a, R.id.botaoAtualizacaoDepois).performClick()
        h.idle()
        assertFalse(modalVisivel(a))

        repeat(3) { h.avancar(30_000); h.deixarRodar(100) }
        h.contexto.sendBroadcast(Intent(Intent.ACTION_MEDIA_MOUNTED, Uri.fromFile(pendrive)))
        h.deixarRodar(300)
        assertFalse("nenhuma repetição", modalVisivel(a))
        assertEquals("o pendrive foi lido uma vez só (o vigia só lista volumes)", 1, leituras.get())

        // O técnico ainda pode instalar pelo bloco técnico.
        abrirPainel(a)
        assertEquals(View.VISIBLE, h.vista<View>(a, R.id.botaoInstalarAtualizacao).visibility)
        h.vista<View>(a, R.id.botaoInstalarAtualizacao).performClick()
        h.idle()
        assertTrue(modalVisivel(a))
        assertEquals(View.GONE, h.vista<View>(a, R.id.telaPin).visibility)
    }

    @Test
    fun `VOLTAR com o modal aberto e Depois e nao pede o PIN`() {
        gravarPacote()
        val a = subirExibindo().get()
        conectar()
        h.esperar { modalVisivel(a) }

        h.voltar(a)

        assertFalse(modalVisivel(a))
        assertEquals(View.GONE, h.vista<View>(a, R.id.telaPin).visibility)
        assertTrue(EstadoAtualizacao.foiDispensado(volume.id))
    }

    @Test
    fun `modal esquecido aberto some sozinho como Depois`() {
        gravarPacote()
        val a = subirExibindo().get()
        conectar()
        h.esperar { modalVisivel(a) }

        h.avancar(br.com.mostrai.player.ui.TelaAtualizacao.INATIVIDADE_MS + 1_000)

        assertFalse(modalVisivel(a))
        assertTrue(EstadoAtualizacao.foiDispensado(volume.id))
    }

    @Test
    fun `pendrive retirado e reconectado volta a oferecer`() {
        gravarPacote()
        val a = subirExibindo().get()
        conectar()
        h.esperar { modalVisivel(a) }
        h.vista<View>(a, R.id.botaoAtualizacaoDepois).performClick()
        h.idle()

        desconectar()
        conectar()

        h.esperar { modalVisivel(a) }
    }

    @Test
    fun `Verificar USB no bloco tecnico oferece de novo`() {
        gravarPacote()
        val a = subirExibindo().get()
        conectar()
        h.esperar { modalVisivel(a) }
        h.vista<View>(a, R.id.botaoAtualizacaoDepois).performClick()
        h.idle()

        abrirPainel(a)
        h.vista<View>(a, R.id.botaoVerificarUsb).performClick()

        h.esperar { modalVisivel(a) }
        assertEquals("nada focável escondido debaixo do modal", View.GONE, h.vista<View>(a, R.id.telaPin).visibility)
    }

    // ------------------------------------------------------------ fora da frente

    @Test
    fun `copia que termina com o Player fora da frente nao vira Depois e e oferecida na volta`() {
        gravarPacote()
        val lendo = java.util.concurrent.CountDownLatch(1)
        val liberar = java.util.concurrent.CountDownLatch(1)
        FontesAtualizacao.leitor = {
            br.com.mostrai.player.atualizacao.LeitorDeApk { f -> lendo.countDown(); liberar.await(); ApkFalso.LeitorFalso.ler(f) }
        }
        val controle = subirExibindo()
        val a = controle.get()
        conectar()
        h.esperar { lendo.count == 0L }

        // TV em standby / HOME no meio da cópia.
        controle.pause().stop()
        liberar.countDown()
        h.esperar { EstadoAtualizacao.candidato != null }
        h.avancar(br.com.mostrai.player.ui.TelaAtualizacao.INATIVIDADE_MS + 1_000)
        assertFalse(modalVisivel(a))
        assertFalse("ninguém disse Depois", EstadoAtualizacao.foiDispensado(volume.id))

        controle.restart().start().resume()
        h.idle()
        assertTrue(modalVisivel(a))
    }

    @Test
    fun `modal aberto quando a TV sai da frente volta quando ela volta`() {
        gravarPacote()
        val controle = subirExibindo()
        val a = controle.get()
        conectar()
        h.esperar { modalVisivel(a) }

        controle.pause().stop()
        assertFalse(modalVisivel(a))
        assertFalse(EstadoAtualizacao.foiDispensado(volume.id))
        controle.restart().start().resume()
        h.idle()

        assertTrue(modalVisivel(a))
    }

    @Test
    fun `Agora nao ao acesso vale depois do reboot enquanto o pendrive nao sai`() {
        gravarPacote()
        acesso = { AcessoUsb.Acesso.PrecisaSeletor }
        conectar(avisar = false)
        val primeiro = subirExibindo()
        h.esperar { modalVisivel(primeiro.get()) }
        h.vista<View>(primeiro.get(), R.id.botaoAtualizacaoDepois).performClick()
        h.idle()
        primeiro.pause().stop().destroy()

        // Reboot: o estado do processo some; o pendrive continua espetado.
        EstadoAtualizacao.reiniciar()
        val segundo = subirExibindo()
        val b = segundo.get()
        h.deixarRodar(500)
        assertFalse("pendrive de outro uso não cobre o anúncio a cada boot", modalVisivel(b))

        // Saiu e voltou: pergunta de novo.
        desconectar()
        conectar()
        h.esperar { modalVisivel(b) }
    }

    // ------------------------------------------------------------ instalar

    @Test
    fun `Atualizar agora entrega a copia validada ao instalador do Android`() {
        gravarPacote()
        val controle = subirExibindo()
        val a = controle.get()
        conectar()
        h.esperar { modalVisivel(a) }

        h.vista<View>(a, R.id.botaoAtualizacaoPrimario).performClick()
        h.idle()

        val intent = shadowOf(a).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals("content", intent.data!!.scheme)
        assertEquals("${BuildConfig.APPLICATION_ID}.atualizacao", intent.data!!.authority)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(modalVisivel(a))
        // O instalador recebe o APK copiado e validado, não o do pendrive.
        h.contexto.contentResolver.openInputStream(intent.data!!)!!.use {
            assertEquals(atual + 1, ApkFalso.LeitorFalso.ler(File(h.contexto.filesDir, "atualizacao/Mostrai-Player.apk"))!!.versionCode)
            assertTrue(it.readBytes().isNotEmpty())
        }
        val watchdog = h.contexto.getSharedPreferences("mostrai_watchdog", Context.MODE_PRIVATE)
        assertTrue("retorno rápido em pausa enquanto o instalador está na frente", watchdog.contains("pausa_ate"))

        // Técnico cancelou no instalador: o Player volta na mesma versão.
        controle.pause().stop()
        controle.restart().start().resume()
        h.idle()

        assertTrue(EstadoAtualizacao.ultimoResultado!!.startsWith("Instalação não concluída"))
        assertFalse(watchdog.contains("pausa_ate"))
    }

    @Test
    fun `sem permissao de instalar o modal pede para liberar e volta depois de liberado`() {
        shadowOf(h.contexto.packageManager).setCanRequestPackageInstalls(false)
        gravarPacote()
        val controle = subirExibindo()
        val a = controle.get()
        conectar()
        h.esperar { modalVisivel(a) }

        assertEquals(
            "Para atualizar o Mostraí pelo pendrive, permita instalações pelo Mostraí Player.",
            texto(a, R.id.mensagemAtualizacao),
        )
        assertEquals("Permitir atualizações", texto(a, R.id.botaoAtualizacaoPrimario))
        h.vista<View>(a, R.id.botaoAtualizacaoPrimario).performClick()
        h.idle()

        val configuracoes = shadowOf(a).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, configuracoes.action)
        assertEquals("package:${BuildConfig.APPLICATION_ID}", configuracoes.dataString)

        // O técnico liberou e voltou.
        shadowOf(h.contexto.packageManager).setCanRequestPackageInstalls(true)
        controle.pause().stop()
        controle.restart().start().resume()
        h.idle()

        assertTrue(modalVisivel(a))
        assertEquals("Nova versão encontrada.", texto(a, R.id.mensagemAtualizacao))
    }

    // ------------------------------------------------------------ recusas

    @Test
    fun `pendrive comum nao mostra nada nem registra recusa`() {
        File(pendrive, "DCIM").mkdirs()
        File(pendrive, "DCIM/foto.jpg").writeBytes(ByteArray(2048))
        val a = subirExibindo().get()
        conectar()
        esperarResultado("Pendrive sem atualização do Mostraí.")
        assertFalse(modalVisivel(a))
        assertTrue(recusas().isEmpty())
    }

    @Test
    fun `APK com outra assinatura e recusado sem modal e fica no diario`() {
        gravarPacote(certificado = "f".repeat(64))
        val a = subirExibindo().get()
        conectar()
        esperarResultado(ValidacaoAtualizacao.Veredito.AssinaturaErrada.mensagem)
        assertFalse(modalVisivel(a))
        assertEquals(1, recusas().size)
        assertTrue(File(h.contexto.filesDir, "atualizacao").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `mesma versao nao mostra modal nem conta como recusa`() {
        gravarPacote(versionCode = atual, versionName = BuildConfig.VERSION_NAME)
        val a = subirExibindo().get()
        conectar()
        esperarResultado("Esta versão já está instalada.")
        assertFalse(modalVisivel(a))
        assertTrue(recusas().isEmpty())
    }

    @Test
    fun `downgrade nao mostra modal`() {
        gravarPacote(versionCode = atual - 2, versionName = "3.0.0")
        val a = subirExibindo().get()
        conectar()
        esperarResultado("Versão do USB anterior à instalada.")
        assertFalse(modalVisivel(a))
    }

    // ------------------------------------------------------------ acesso ao pendrive

    @Test
    fun `Android 11+ - Permitir acesso abre o seletor e a pasta autorizada traz a atualizacao`() {
        gravarPacote()
        acesso = { AcessoUsb.Acesso.PrecisaSeletor }
        val a = subirExibindo().get()
        conectar()
        h.esperar { modalVisivel(a) }
        assertEquals("Permitir acesso", texto(a, R.id.botaoAtualizacaoPrimario))
        assertEquals("Agora não", texto(a, R.id.botaoAtualizacaoDepois))

        h.vista<View>(a, R.id.botaoAtualizacaoPrimario).performClick()
        h.idle()
        val pedido = shadowOf(a).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, pedido.intent.action)

        acesso = { AcessoUsb.Acesso.Direto(it.raiz!!) }
        shadowOf(a).receiveResult(
            pedido.intent, Activity.RESULT_OK,
            Intent().setData(Uri.parse("content://com.android.externalstorage.documents/tree/ABCD-1234%3A")),
        )

        h.esperar { modalVisivel(a) && texto(a, R.id.mensagemAtualizacao) == "Nova versão encontrada." }
    }

    @Test
    @Config(sdk = [26])
    fun `TCL Android 8 - Permitir acesso pede a leitura do armazenamento e, concedida, oferece`() {
        gravarPacote()
        acesso = { AcessoUsb.Acesso.PrecisaPermissaoLeitura }
        val a = subirExibindo().get()
        conectar()
        h.esperar { modalVisivel(a) }

        h.vista<View>(a, R.id.botaoAtualizacaoPrimario).performClick()
        h.idle()
        val pedido = shadowOf(a).lastRequestedPermission
        assertNotNull(pedido)
        assertEquals(listOf(Manifest.permission.READ_EXTERNAL_STORAGE), pedido.requestedPermissions.toList())

        acesso = { AcessoUsb.Acesso.Direto(it.raiz!!) }
        shadowOf(h.contexto as android.app.Application).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        a.onRequestPermissionsResult(pedido.requestCode, pedido.requestedPermissions, intArrayOf(PackageManager.PERMISSION_GRANTED))

        h.esperar { modalVisivel(a) && texto(a, R.id.mensagemAtualizacao) == "Nova versão encontrada." }
    }

    @Test
    fun `Agora nao vale ate o pendrive sair`() {
        gravarPacote()
        acesso = { AcessoUsb.Acesso.PrecisaSeletor }
        val a = subirExibindo().get()
        conectar()
        h.esperar { modalVisivel(a) }
        h.vista<View>(a, R.id.botaoAtualizacaoDepois).performClick()
        h.idle()

        repeat(2) { h.avancar(30_000); h.deixarRodar(100) }
        assertFalse(modalVisivel(a))
        assertEquals("Pendrive conectado; falta autorizar a pasta do pendrive.", EstadoAtualizacao.ultimoResultado)
    }

    @Test
    fun `Android que nao deixa ler o pendrive nao incomoda, so registra no bloco tecnico`() {
        gravarPacote()
        acesso = { AcessoUsb.Acesso.Indisponivel }
        val a = subirExibindo().get()
        conectar()
        esperarResultado("Pendrive conectado, mas este Android não deixa o Mostraí ler o pendrive.")
        assertFalse(modalVisivel(a))
    }

    // ------------------------------------------------------------ bloco técnico

    @Test
    fun `bloco tecnico mostra a secao ATUALIZACAO sem caminho nem hash`() {
        gravarPacote()
        val a = subirExibindo().get()
        conectar()
        h.esperar { modalVisivel(a) }
        h.vista<View>(a, R.id.botaoAtualizacaoDepois).performClick()
        h.idle()

        abrirPainel(a)
        val info = texto(a, R.id.infoSuporte)

        assertTrue(info, info.contains("Versão instalada: ${BuildConfig.VERSION_NAME}"))
        assertTrue(info, info.contains("Permissão para instalar: concedida"))
        assertTrue(info, info.contains("USB conectado: sim"))
        assertTrue(info, info.contains("Versão encontrada: 3.0.2 (${atual + 1})"))
        assertTrue(info, info.contains("Último resultado: Atualização 3.0.2 pronta para instalar."))
        assertFalse("sem caminho", info.contains(pendrive.absolutePath) || info.contains("/storage"))
        assertFalse("sem hash", info.contains(oficial))
    }

    private fun receptoresDePendrive() = shadowOf(h.contexto as android.app.Application).registeredReceivers
        .filter { it.intentFilter.hasAction(Intent.ACTION_MEDIA_MOUNTED) }

    @Test
    fun `fora da frente o Player nao le pendrive`() {
        gravarPacote()
        val controle = subirExibindo()
        assertEquals(1, receptoresDePendrive().size)
        controle.pause().stop()
        assertTrue("aviso de pendrive desligado fora da frente", receptoresDePendrive().isEmpty())

        conectar()
        h.avancar(60_000)
        h.deixarRodar(300)

        assertNull(EstadoAtualizacao.ultimoResultado)
        assertNull(EstadoAtualizacao.candidato)
    }
}
