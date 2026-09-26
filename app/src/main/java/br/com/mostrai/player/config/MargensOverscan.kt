package br.com.mostrai.player.config

/**
 * Margem de overscan em vmin, um valor por lado — a folga que uma TV corta
 * na borda da imagem varia por lado, não só por tela. Sempre em termos
 * visuais (o que o espectador vê olhando pra tela montada): `RotacaoTela`
 * aplica isso depois de compensar a rotação fixa do APK, então "topo" aqui
 * é sempre o topo que o espectador enxerga, não a borda física do painel.
 * Chega em vmin, 0 a 10 por lado, em `margens` da config (contrato §6).
 */
data class MargensOverscan(
    val topo: Float = 0f,
    val base: Float = 0f,
    val esquerda: Float = 0f,
    val direita: Float = 0f,
)
