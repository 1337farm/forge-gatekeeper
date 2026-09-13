package com.forgerig.gatekeeper.engine

object TokenEstimator {
    fun count(text: String): Int = (text.length / 4).coerceAtLeast(1)

    fun ratio(pre: Int, post: Int): Double {
        if (pre <= 0) return 0.0
        return ((pre - post).toDouble() / pre * 100.0).coerceIn(0.0, 100.0)
    }
}
