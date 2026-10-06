package br.com.mostrai.player.atualizacao

import android.content.Context

/**
 * De onde a Activity tira pendrives, leitura de APK e o próprio Player
 * instalado. Trocável **só em teste** (o Robolectric não monta pendrive nem
 * lê assinatura de APK de verdade); [padrao] devolve o comportamento real.
 */
internal object FontesAtualizacao {
    @Volatile var volumes: (Context) -> FonteVolumes = { VolumesAndroid(it) }
    @Volatile var leitor: (Context) -> LeitorDeApk = { LeitorDeApkAndroid(it) }
    @Volatile var instalado: (Context) -> InfoApk = { LeitorDeApkAndroid(it).instalado() }
    @Volatile var acesso: (Context, VolumeUsb) -> AcessoUsb.Acesso = { c, v -> AcessoUsb.decidir(c, v) }

    fun padrao() {
        volumes = { VolumesAndroid(it) }
        leitor = { LeitorDeApkAndroid(it) }
        instalado = { LeitorDeApkAndroid(it).instalado() }
        acesso = { c, v -> AcessoUsb.decidir(c, v) }
    }
}
