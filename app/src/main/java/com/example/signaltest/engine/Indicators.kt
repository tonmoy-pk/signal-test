package com.example.signaltest.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

object Ind {

    fun sma(v: DoubleArray, p: Int): DoubleArray {
        val out = DoubleArray(v.size) { Double.NaN }
        if (p <= 0 || v.size < p) return out
        var sum = 0.0
        for (i in v.indices) {
            sum += v[i]
            if (i >= p) sum -= v[i - p]
            if (i >= p - 1) out[i] = sum / p
        }
        return out
    }

    /** EMA: প্রথম p মানের SMA দিয়ে শুরু, তার আগে NaN */
    fun ema(v: DoubleArray, p: Int): DoubleArray {
        val out = DoubleArray(v.size) { Double.NaN }
        if (p <= 0 || v.size < p) return out
        var seed = 0.0
        for (i in 0 until p) seed += v[i]
        var prev = seed / p
        out[p - 1] = prev
        val k = 2.0 / (p + 1)
        for (i in p until v.size) {
            prev = v[i] * k + prev * (1 - k)
            out[i] = prev
        }
        return out
    }

    /** Wilder RSI */
    fun rsi(v: DoubleArray, p: Int = 14): DoubleArray {
        val out = DoubleArray(v.size) { Double.NaN }
        if (v.size <= p) return out
        var gain = 0.0
        var loss = 0.0
        for (i in 1..p) {
            val d = v[i] - v[i - 1]
            if (d >= 0) gain += d else loss -= d
        }
        var avgG = gain / p
        var avgL = loss / p
        out[p] = rsiFrom(avgG, avgL)
        for (i in p + 1 until v.size) {
            val d = v[i] - v[i - 1]
            val g = if (d > 0) d else 0.0
            val l = if (d < 0) -d else 0.0
            avgG = (avgG * (p - 1) + g) / p
            avgL = (avgL * (p - 1) + l) / p
            out[i] = rsiFrom(avgG, avgL)
        }
        return out
    }

    private fun rsiFrom(g: Double, l: Double): Double {
        if (l == 0.0) return if (g == 0.0) 50.0 else 100.0
        val rs = g / l
        return 100.0 - 100.0 / (1.0 + rs)
    }

    class Macd(val line: DoubleArray, val signal: DoubleArray, val hist: DoubleArray)

    fun macd(v: DoubleArray, fast: Int = 12, slow: Int = 26, sig: Int = 9): Macd {
        val n = v.size
        val line = DoubleArray(n) { Double.NaN }
        val signal = DoubleArray(n) { Double.NaN }
        val hist = DoubleArray(n) { Double.NaN }
        if (n < slow) return Macd(line, signal, hist)
        val ef = ema(v, fast)
        val es = ema(v, slow)
        for (i in slow - 1 until n) line[i] = ef[i] - es[i]
        val tail = line.copyOfRange(slow - 1, n)
        val sg = ema(tail, sig)
        for (i in sg.indices) {
            val idx = slow - 1 + i
            signal[idx] = sg[i]
            if (!sg[i].isNaN()) hist[idx] = line[idx] - sg[i]
        }
        return Macd(line, signal, hist)
    }

    class Bands(val mid: DoubleArray, val up: DoubleArray, val low: DoubleArray)

    fun bollinger(v: DoubleArray, p: Int = 20, k: Double = 2.0): Bands {
        val n = v.size
        val mid = DoubleArray(n) { Double.NaN }
        val up = DoubleArray(n) { Double.NaN }
        val low = DoubleArray(n) { Double.NaN }
        for (i in p - 1 until n) {
            var s = 0.0
            for (j in i - p + 1..i) s += v[j]
            val m = s / p
            var q = 0.0
            for (j in i - p + 1..i) q += (v[j] - m) * (v[j] - m)
            val sd = sqrt(q / p)
            mid[i] = m
            up[i] = m + k * sd
            low[i] = m - k * sd
        }
        return Bands(mid, up, low)
    }

    /** শেষ p ক্যান্ডেলের গড় True Range */
    fun atrLast(h: DoubleArray, l: DoubleArray, c: DoubleArray, p: Int = 14): Double {
        val n = c.size
        if (n < 2) return Double.NaN
        val start = max(1, n - p)
        var sum = 0.0
        var cnt = 0
        for (i in start until n) {
            val tr = max(h[i] - l[i], max(abs(h[i] - c[i - 1]), abs(l[i] - c[i - 1])))
            sum += tr
            cnt++
        }
        return if (cnt == 0) Double.NaN else sum / cnt
    }
}
