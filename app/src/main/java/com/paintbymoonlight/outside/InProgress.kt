package com.paintbymoonlight.outside

import com.paintbymoonlight.data.PaintingMeta

/**
 * The painting the widget and the Continue shortcut offer: the unfinished one painted (or
 * made) most recently. Null when every painting is finished, or there is none.
 */
fun inProgress(paintings: List<PaintingMeta>): PaintingMeta? =
    paintings.filter { it.finished == null }.maxByOrNull { maxOf(it.updated, it.created) }

/**
 * Whole percent painted, rounded down; 0 only when nothing is painted and 100 only when
 * everything is, so a painting one area from done never reads 100%. The iOS app's rule
 * (`OutsideShelf.percent`).
 */
fun percentPainted(painted: Int, total: Int): Int = when {
    total <= 0 || painted <= 0 -> 0
    painted >= total -> 100
    else -> (painted * 100 / total).coerceIn(1, 99)
}
