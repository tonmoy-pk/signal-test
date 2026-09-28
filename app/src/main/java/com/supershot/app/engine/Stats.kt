package com.supershot.app.engine

import kotlin.math.sqrt

object Stats {

    /** Wilson আস্থা-সীমা (z=1.96 → ৯৫%) */
    fun wilson(wins: Int, n: Int, z: Double = 1.96): DoubleArray {
        if (n <= 0) return doubleArrayOf(0.0, 1.0)
        val p = wins.toDouble() / n
        val z2 = z * z
        val denom = 1 + z2 / n
        val centre = (p + z2 / (2 * n)) / denom
        val half = z * sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / denom
        return doubleArrayOf(centre - half, centre + half)
    }

    /** পেআউট ৮৫% হলে ব্রেক-ইভেন = 1/1.85 */
    fun breakEven(payoutPct: Int): Double = 100.0 / (100.0 + payoutPct)
}
