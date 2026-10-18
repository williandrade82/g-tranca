package com.gtranca.ui.game

/**
 * Posições (à esquerda de cada carta) do lixo numa linha de largura [available], com cartas de largura [cardWidth]
 * (§5.6: todas visíveis). Cabendo com o passo normal [maxStep], usa-o. Senão as cartas se espremem: o espaço entre
 * duas cartas cresce das mais antigas para as mais novas (proporcional à posição), de modo que a última, sempre
 * por cima, fica inteira dentro da linha. Mesma unidade em todos os valores (dp).
 */
fun pileOffsets(count: Int, available: Float, cardWidth: Float, maxStep: Float): List<Float> {
    if (count <= 0) return emptyList()
    if (count == 1) return listOf(0f)
    val room = (available - cardWidth).coerceAtLeast(0f)
    val gaps = count - 1
    if (maxStep * gaps <= room) return List(count) { it * maxStep }
    // Espaços 1, 2, ..., gaps (relativos) escalados para somar exatamente o espaço disponível.
    val unit = room / (gaps * (gaps + 1) / 2f)
    val offsets = ArrayList<Float>(count)
    var x = 0f
    offsets += x
    for (g in 1..gaps) {
        x += unit * g
        offsets += x
    }
    return offsets
}
