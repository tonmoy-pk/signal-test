package com.supershot.app.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * সিগন্যালের নিয়ম। সব নিয়ম আগে থেকে ঠিক করা, ব্যবহারের সময় বদলানো চলবে না।
 * সংখ্যা সবসময় কোড থেকে আসে।
 */
object SignalEngine {

    const val MIN_CANDLES = 25

    val SETUP_IDS = listOf(
        "BB_RSI", "BB_SQUEEZE", "BB_WALK",
        "EMA_PULLBACK", "EMA_CROSS",
        "SR_REJECT", "SR_APPROACH", "SR_BREAKOUT", "ROLE_REVERSAL",
        "REV_PATTERN",
        "RSI_DIVERGENCE",
        "MACD_TREND", "MACD_ZERO", "MACD_HIST"
    )

    fun setupTitle(id: String): String = when (id) {
        "BB_RSI" -> "Bollinger + RSI প্রান্ত"
        "EMA_PULLBACK" -> "EMA পুলব্যাক"
        "SR_REJECT" -> "সাপোর্ট/রেজিস্ট্যান্স প্রত্যাখ্যান"
        "REV_PATTERN" -> "ক্যান্ডেল রিভার্সাল প্যাটার্ন"
        "MACD_TREND" -> "MACD ক্রস + ট্রেন্ড"
        "BB_SQUEEZE" -> "Bollinger স্কুইজ ব্রেকআউট"
        "BB_WALK" -> "Bollinger ব্যান্ড ওয়াক (ট্রেন্ড চলছে)"
        "EMA_CROSS" -> "EMA20/50 ক্রসওভার"
        "SR_APPROACH" -> "লেভেলের কাছে আসা"
        "SR_BREAKOUT" -> "লেভেল ব্রেকআউট"
        "ROLE_REVERSAL" -> "রোল রিভার্সাল (ভাঙা লেভেলে রিটেস্ট)"
        "RSI_DIVERGENCE" -> "RSI ডাইভারজেন্স"
        "MACD_ZERO" -> "MACD জিরো লাইন ক্রস"
        "MACD_HIST" -> "MACD হিস্টোগ্রাম গতি বদল"
        else -> id
    }

    private fun wait(reason: String, n: Int, info: String) =
        Analysis(true, null, emptyList(), reason, n, info)

    fun analyze(reading: ChartReading, minSetups: Int = 1): Analysis {
        if (!reading.ok) return Analysis(false, null, emptyList(), "NO_CHART", 0, reading.message)
        val cs = reading.candles
        val n = cs.size
        if (n < MIN_CANDLES) {
            return wait("FEW_CANDLES", n, "ক্যান্ডেল $n টি (ন্যূনতম $MIN_CANDLES)। চার্ট জুম আউট করো")
        }
        val lastC = cs[n - 1]
        if (lastC.cut) return wait("HIDDEN_CANDLE", n, "সর্বশেষ ক্যান্ডেল আইকন/বোতামে ঢাকা")

        val s = ChartReader.toSeries(cs)
        val o = s.o; val h = s.h; val l = s.l; val c = s.c
        val atr = Ind.atrLast(h, l, c, 14)
        if (atr.isNaN() || atr < 4.0) return wait("FLAT_MARKET", n, "বাজার প্রায় স্থির (ATR ${fmt(atr)}px)")

        val rsi = Ind.rsi(c, 14)
        val bb = Ind.bollinger(c, 20, 2.0)
        val ema20 = Ind.ema(c, 20)
        val ema50 = Ind.ema(c, 50)
        val macd = Ind.macd(c, 12, 26, 9)
        val i = n - 1

        val found = ArrayList<Setup>()

        // ১. Bollinger প্রান্ত + RSI
        if (!bb.up[i].isNaN() && !rsi[i].isNaN()) {
            if (l[i] <= bb.low[i] && rsi[i] <= 30.0) {
                found.add(Setup("BB_RSI", Dir.UP, "নিচের ব্যান্ড ছুঁয়েছে, RSI ${fmt(rsi[i])}"))
            } else if (h[i] >= bb.up[i] && rsi[i] >= 70.0) {
                found.add(Setup("BB_RSI", Dir.DOWN, "ওপরের ব্যান্ড ছুঁয়েছে, RSI ${fmt(rsi[i])}"))
            }
        }

        // ২. EMA পুলব্যাক (ট্রেন্ডের দিকে ফেরা)
        if (!ema50[i].isNaN() && i >= 6 && !ema50[i - 5].isNaN() && !ema20[i].isNaN()) {
            val e20 = ema20[i]; val e50 = ema50[i]
            val slope = e50 - ema50[i - 5]
            val tol = 0.25 * atr
            if (e20 > e50 && slope > 0 && l[i] <= e20 + tol && c[i] >= e20 - tol && c[i] > o[i]) {
                found.add(Setup("EMA_PULLBACK", Dir.UP, "আপট্রেন্ডে EMA20 ছুঁয়ে উঠছে"))
            } else if (e20 < e50 && slope < 0 && h[i] >= e20 - tol && c[i] <= e20 + tol && c[i] < o[i]) {
                found.add(Setup("EMA_PULLBACK", Dir.DOWN, "ডাউনট্রেন্ডে EMA20 ছুঁয়ে নামছে"))
            }
        }

        // ৩. সাপোর্ট/রেজিস্ট্যান্স প্রত্যাখ্যান
        val levels = supportResistance(s, cs, atr)
        run {
            val rng = h[i] - l[i]
            if (rng > 0) {
                val tol = 0.35 * atr
                val lowerWick = (min(o[i], c[i]) - l[i]) / rng
                val upperWick = (h[i] - max(o[i], c[i])) / rng
                for (lv in levels) {
                    if (abs(l[i] - lv) <= tol && c[i] > lv && lowerWick >= 0.4) {
                        found.add(Setup("SR_REJECT", Dir.UP, "সাপোর্ট লেভেল থেকে ফেরা"))
                        break
                    }
                    if (abs(h[i] - lv) <= tol && c[i] < lv && upperWick >= 0.4) {
                        found.add(Setup("SR_REJECT", Dir.DOWN, "রেজিস্ট্যান্স থেকে ফেরা"))
                        break
                    }
                }
            }
        }

        // ৪. রিভার্সাল প্যাটার্ন + RSI প্রেক্ষাপট
        if (!rsi[i].isNaN()) {
            val bull = ArrayList<String>()
            val bear = ArrayList<String>()
            Patterns.detect(s, cs, atr, bull, bear)
            if (bull.isNotEmpty() && rsi[i] <= 40.0) {
                found.add(Setup("REV_PATTERN", Dir.UP, bull.joinToString("/") + ", RSI ${fmt(rsi[i])}"))
            } else if (bear.isNotEmpty() && rsi[i] >= 60.0) {
                found.add(Setup("REV_PATTERN", Dir.DOWN, bear.joinToString("/") + ", RSI ${fmt(rsi[i])}"))
            }
        }

        // ৫. MACD ক্রস + EMA20 ট্রেন্ড ফিল্টার
        if (i >= 1 && !macd.hist[i].isNaN() && !macd.hist[i - 1].isNaN() && !ema20[i].isNaN()) {
            val hNow = macd.hist[i]; val hPrev = macd.hist[i - 1]
            if (hPrev <= 0 && hNow > 0 && c[i] > ema20[i]) {
                found.add(Setup("MACD_TREND", Dir.UP, "MACD ওপরে ক্রস, দাম EMA20-র ওপরে"))
            } else if (hPrev >= 0 && hNow < 0 && c[i] < ema20[i]) {
                found.add(Setup("MACD_TREND", Dir.DOWN, "MACD নিচে ক্রস, দাম EMA20-র নিচে"))
            }
        }

        // ৬. Bollinger স্কুইজ ব্রেকআউট
        if (n >= 40 && !bb.up[i].isNaN() && !bb.up[i - 1].isNaN()) {
            var minW = Double.MAX_VALUE
            for (k in n - 40 until n) {
                val wv = bb.up[k] - bb.low[k]
                if (!wv.isNaN() && wv < minW) minW = wv
            }
            var recentMin = Double.MAX_VALUE
            for (k in n - 6 until n - 1) {
                val wv = bb.up[k] - bb.low[k]
                if (!wv.isNaN() && wv < recentMin) recentMin = wv
            }
            val wNow = bb.up[i] - bb.low[i]
            val wPrev = bb.up[i - 1] - bb.low[i - 1]
            if (recentMin <= minW * 1.25 && wNow > wPrev * 1.3) {
                if (c[i] > bb.up[i]) found.add(Setup("BB_SQUEEZE", Dir.UP, "স্কুইজ ভেঙে ওপরে"))
                else if (c[i] < bb.low[i]) found.add(Setup("BB_SQUEEZE", Dir.DOWN, "স্কুইজ ভেঙে নিচে"))
            }
        }


        // ৭. Bollinger ব্যান্ড ওয়াক: শেষ ৪ ক্যান্ডেলের অন্তত ৩টির ক্লোজ ব্যান্ড ঘেঁষে
        if (i >= 3 && !bb.up[i - 3].isNaN()) {
            var upCnt = 0
            var loCnt = 0
            for (k in i - 3..i) {
                if (c[k] >= bb.up[k] - 0.1 * atr) upCnt++
                if (c[k] <= bb.low[k] + 0.1 * atr) loCnt++
            }
            if (upCnt >= 3 && c[i] > o[i]) found.add(Setup("BB_WALK", Dir.UP, "ওপরের ব্যান্ড ধরে উঠছে"))
            else if (loCnt >= 3 && c[i] < o[i]) found.add(Setup("BB_WALK", Dir.DOWN, "নিচের ব্যান্ড ধরে নামছে"))
        }

        // ৮. EMA20/50 ক্রসওভার (শেষ ২ ক্যান্ডেলের মধ্যে ঘটেছে)
        if (i >= 3 && !ema50[i - 2].isNaN() && !ema20[i].isNaN()) {
            val crossUp = (ema20[i - 1] <= ema50[i - 1] && ema20[i] > ema50[i]) ||
                (ema20[i - 2] <= ema50[i - 2] && ema20[i - 1] > ema50[i - 1] && ema20[i] > ema50[i])
            val crossDn = (ema20[i - 1] >= ema50[i - 1] && ema20[i] < ema50[i]) ||
                (ema20[i - 2] >= ema50[i - 2] && ema20[i - 1] < ema50[i - 1] && ema20[i] < ema50[i])
            if (crossUp && c[i] > ema20[i]) found.add(Setup("EMA_CROSS", Dir.UP, "EMA20 ওপরে ক্রস করেছে EMA50"))
            else if (crossDn && c[i] < ema20[i]) found.add(Setup("EMA_CROSS", Dir.DOWN, "EMA20 নিচে ক্রস করেছে EMA50"))
        }

        // ৯. সাপোর্ট/রেজিস্ট্যান্স: কাছে আসা / ব্রেকআউট / রোল রিভার্সাল
        if (i >= 3) {
            var approach = false
            var breakout = false
            var role = false
            for (lv in levels) {
                if (!approach && !rsi[i].isNaN()) {
                    if (c[i] < lv && h[i] < lv - 0.05 * atr && lv - c[i] <= 0.5 * atr && c[i] > c[i - 2] && rsi[i] >= 60.0) {
                        found.add(Setup("SR_APPROACH", Dir.DOWN, "রেজিস্ট্যান্সের কাছে উঠছে, RSI ${fmt(rsi[i])}"))
                        approach = true
                    } else if (c[i] > lv && l[i] > lv + 0.05 * atr && c[i] - lv <= 0.5 * atr && c[i] < c[i - 2] && rsi[i] <= 40.0) {
                        found.add(Setup("SR_APPROACH", Dir.UP, "সাপোর্টের কাছে নামছে, RSI ${fmt(rsi[i])}"))
                        approach = true
                    }
                }
                if (!breakout) {
                    val body = abs(c[i] - o[i])
                    if (c[i - 1] <= lv + 0.1 * atr && c[i] >= lv + 0.25 * atr && c[i] > o[i] && body >= 0.5 * atr) {
                        found.add(Setup("SR_BREAKOUT", Dir.UP, "রেজিস্ট্যান্স ভেঙে ওপরে"))
                        breakout = true
                    } else if (c[i - 1] >= lv - 0.1 * atr && c[i] <= lv - 0.25 * atr && c[i] < o[i] && body >= 0.5 * atr) {
                        found.add(Setup("SR_BREAKOUT", Dir.DOWN, "সাপোর্ট ভেঙে নিচে"))
                        breakout = true
                    }
                }
                if (!role && i >= 8) {
                    val from = max(0, i - 30)
                    // আগে নিচে ছিল, তারপর ওপরে ভেঙেছে, ওপরে টিকেছে, এখন রিটেস্ট
                    var kFirst = -1
                    for (k in max(0, i - 15)..i - 3) if (c[k] >= lv + 0.25 * atr) { kFirst = k; break }
                    if (kFirst > 0) {
                        var was = false
                        for (j in from until kFirst) if (c[j] <= lv - 0.25 * atr) { was = true; break }
                        var hold = true
                        for (m in kFirst until i) if (c[m] < lv - 0.35 * atr) { hold = false; break }
                        if (was && hold && l[i] <= lv + 0.3 * atr && c[i] >= lv && c[i] > o[i]) {
                            found.add(Setup("ROLE_REVERSAL", Dir.UP, "ভাঙা রেজিস্ট্যান্স এখন সাপোর্ট, রিটেস্ট থেকে উঠছে"))
                            role = true
                        }
                    }
                    if (!role) {
                        var kf = -1
                        for (k in max(0, i - 15)..i - 3) if (c[k] <= lv - 0.25 * atr) { kf = k; break }
                        if (kf > 0) {
                            var was = false
                            for (j in from until kf) if (c[j] >= lv + 0.25 * atr) { was = true; break }
                            var hold = true
                            for (m in kf until i) if (c[m] > lv + 0.35 * atr) { hold = false; break }
                            if (was && hold && h[i] >= lv - 0.3 * atr && c[i] <= lv && c[i] < o[i]) {
                                found.add(Setup("ROLE_REVERSAL", Dir.DOWN, "ভাঙা সাপোর্ট এখন রেজিস্ট্যান্স, রিটেস্ট থেকে নামছে"))
                                role = true
                            }
                        }
                    }
                }
            }
        }

        // ১০. RSI ডাইভারজেন্স
        if (i >= 8 && !rsi[i].isNaN()) {
            // লুকব্যাকে সবচেয়ে নিচের সুইং লো ও সবচেয়ে ওপরের সুইং হাই (সমান হলে নতুনটা)
            var pLow = -1
            var pHigh = -1
            for (m in i - 4 downTo max(2, i - 30)) {
                if (cs[m].cut || rsi[m].isNaN()) continue
                if (l[m] <= l[m - 1] && l[m] <= l[m - 2] && l[m] <= l[m + 1] && l[m] <= l[m + 2]) {
                    if (pLow < 0 || l[m] < l[pLow]) pLow = m
                }
                if (h[m] >= h[m - 1] && h[m] >= h[m - 2] && h[m] >= h[m + 1] && h[m] >= h[m + 2]) {
                    if (pHigh < 0 || h[m] > h[pHigh]) pHigh = m
                }
            }
            if (pLow >= 0 && l[i] <= l[pLow] - 0.1 * atr && rsi[i] >= rsi[pLow] + 3.0 && rsi[i] <= 45.0 && c[i] > o[i]) {
                found.add(Setup("RSI_DIVERGENCE", Dir.UP, "দাম নতুন লো, RSI ${fmt(rsi[i])} > আগের ${fmt(rsi[pLow])}"))
            } else if (pHigh >= 0 && h[i] >= h[pHigh] + 0.1 * atr && rsi[i] <= rsi[pHigh] - 3.0 && rsi[i] >= 55.0 && c[i] < o[i]) {
                found.add(Setup("RSI_DIVERGENCE", Dir.DOWN, "দাম নতুন হাই, RSI ${fmt(rsi[i])} < আগের ${fmt(rsi[pHigh])}"))
            }
        }

        // ১১. MACD জিরো লাইন ক্রস
        if (i >= 1 && !macd.line[i].isNaN() && !macd.line[i - 1].isNaN() && !macd.hist[i].isNaN()) {
            if (macd.line[i - 1] <= 0 && macd.line[i] > 0 && macd.hist[i] > 0) {
                found.add(Setup("MACD_ZERO", Dir.UP, "MACD লাইন জিরোর ওপরে উঠেছে"))
            } else if (macd.line[i - 1] >= 0 && macd.line[i] < 0 && macd.hist[i] < 0) {
                found.add(Setup("MACD_ZERO", Dir.DOWN, "MACD লাইন জিরোর নিচে নেমেছে"))
            }
        }

        // ১২. MACD হিস্টোগ্রাম: ৩ ক্যান্ডেল ধরে গতি কমছে/বাড়ছে, রঙ উল্টো দিকে
        if (i >= 8 && !macd.hist[i - 2].isNaN() && !macd.hist[i - 6].isNaN()) {
            var maxAbs = 0.0
            for (k in i - 6..i - 2) maxAbs = max(maxAbs, abs(macd.hist[k]))
            if (maxAbs >= 0.1 * atr) {
                val hh = macd.hist
                if (hh[i] < 0 && hh[i] > hh[i - 1] && hh[i - 1] > hh[i - 2] && c[i] > o[i]) {
                    found.add(Setup("MACD_HIST", Dir.UP, "নিচের দিকের গতি কমছে"))
                } else if (hh[i] > 0 && hh[i] < hh[i - 1] && hh[i - 1] < hh[i - 2] && c[i] < o[i]) {
                    found.add(Setup("MACD_HIST", Dir.DOWN, "ওপরের দিকের গতি কমছে"))
                }
            }
        }

        val info = "RSI ${fmt(rsi[i])} | ATR ${fmt(atr)}px | ক্যান্ডেল $n | লেভেল ${levels.size}" +
            (if (reading.gaps > 0) " | ঢাকা ${reading.gaps}" else "")

        val ups = found.count { it.dir == Dir.UP }
        val downs = found.count { it.dir == Dir.DOWN }
        val need = max(1, minSetups)
        return when {
            ups >= need && downs == 0 -> Analysis(true, Dir.UP, found, "OK", n, info)
            downs >= need && ups == 0 -> Analysis(true, Dir.DOWN, found, "OK", n, info)
            ups > 0 && downs > 0 -> Analysis(true, null, found, "CONFLICT", n, info)
            found.isNotEmpty() -> Analysis(true, null, found, "NEED_MORE", n, info)
            else -> Analysis(true, null, found, "NO_SETUP", n, info)
        }
    }

    private fun fmt(v: Double): String = if (v.isNaN()) "-" else String.format(java.util.Locale.US, "%.1f", v)

    /** সুইং হাই/লো থেকে দুবার+ ছোঁয়া লেভেল */
    fun supportResistance(s: Series, cs: List<Candle>, atr: Double): List<Double> {
        val n = s.n
        val pts = ArrayList<Double>()
        for (i in 2..n - 3) {
            if (cs[i].cut) continue
            val hi = s.h[i]
            if (hi >= s.h[i - 1] && hi >= s.h[i - 2] && hi >= s.h[i + 1] && hi >= s.h[i + 2]) pts.add(hi)
            val lo = s.l[i]
            if (lo <= s.l[i - 1] && lo <= s.l[i - 2] && lo <= s.l[i + 1] && lo <= s.l[i + 2]) pts.add(lo)
        }
        pts.sort()
        val tol = 0.4 * atr
        val out = ArrayList<Double>()
        var sum = 0.0
        var cnt = 0
        for (p in pts) {
            if (cnt > 0 && p - sum / cnt > tol) {
                if (cnt >= 2) out.add(sum / cnt)
                sum = 0.0; cnt = 0
            }
            sum += p; cnt++
        }
        if (cnt >= 2) out.add(sum / cnt)
        return out
    }
}

object Patterns {

    /** সর্বশেষ ক্যান্ডেলগুলোয় প্যাটার্ন খোঁজে; আইকনে ঢাকা (cut) ক্যান্ডেল থাকলে ওই প্যাটার্ন বাদ */
    fun detect(s: Series, cs: List<Candle>, atr: Double, bull: MutableList<String>, bear: MutableList<String>) {
        val n = s.n
        if (n < 3) return
        val i = n - 1
        val o = s.o; val h = s.h; val l = s.l; val c = s.c

        val body = abs(c[i] - o[i])
        val rng = h[i] - l[i]
        val lowerW = min(o[i], c[i]) - l[i]
        val upperW = h[i] - max(o[i], c[i])

        if (rng >= 0.8 * atr) {
            if (lowerW >= 2 * body && lowerW >= 0.55 * rng && upperW <= 0.25 * rng) bull.add("Hammer")
            if (upperW >= 2 * body && upperW >= 0.55 * rng && lowerW <= 0.25 * rng) bear.add("ShootingStar")
        }

        if (rng >= 0.8 * atr && body <= 0.1 * rng && n >= 5) {
            if (c[i - 1] < c[i - 4]) bull.add("Doji") else if (c[i - 1] > c[i - 4]) bear.add("Doji")
        }
        if (!cs[i - 1].cut && !cs[i - 2].cut && rng >= atr) {
            if (lowerW >= 0.66 * rng && l[i] < l[i - 1] && l[i] < l[i - 2]) bull.add("PinBar")
            if (upperW >= 0.66 * rng && h[i] > h[i - 1] && h[i] > h[i - 2]) bear.add("PinBar")
        }

        if (!cs[i - 1].cut) {
            val pBody = abs(c[i - 1] - o[i - 1])
            val pTop = max(o[i - 1], c[i - 1]); val pBot = min(o[i - 1], c[i - 1])
            val cTop = max(o[i], c[i]); val cBot = min(o[i], c[i])
            if (pBody >= 0.3 * atr && body >= 1.1 * pBody && cTop >= pTop && cBot <= pBot) {
                if (c[i - 1] < o[i - 1] && c[i] > o[i]) bull.add("BullEngulf")
                if (c[i - 1] > o[i - 1] && c[i] < o[i]) bear.add("BearEngulf")
            }
        }

        if (!cs[i - 1].cut && !cs[i - 2].cut) {
            val b3 = abs(c[i - 2] - o[i - 2])
            val b2 = abs(c[i - 1] - o[i - 1])
            val mid3 = (o[i - 2] + c[i - 2]) / 2
            if (b3 >= 0.5 * atr && b2 <= 0.3 * b3) {
                if (c[i - 2] < o[i - 2] && c[i] > o[i] && c[i] >= mid3) bull.add("MorningStar")
                if (c[i - 2] > o[i - 2] && c[i] < o[i] && c[i] <= mid3) bear.add("EveningStar")
            }
        }
    }
}
