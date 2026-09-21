package com.example.signaltest.engine

/** ডেমো টেস্টের লগ: সিগন্যাল, ফলাফল ও স্কিপ। Android-মুক্ত, তাই আলাদাভাবে পরীক্ষা করা যায়। */
data class Sig(
    val id: Long,
    val ts: Long,
    val dir: String,
    val setups: List<String>,
    val n: Int,
    val tf: Int,
    val payout: Int,
    var result: String
)

class LogData(val signals: List<Sig>, val skips: Map<String, Int>)

class Tally(val wins: Int, val losses: Int, val ties: Int, val profit: Double) {
    val n: Int get() = wins + losses
    val rate: Double get() = if (n == 0) 0.0 else wins.toDouble() / n
}

object LogModel {
    const val WIN = "WIN"
    const val LOSS = "LOSS"
    const val TIE = "TIE"
    const val VOID = "VOID"
    const val PENDING = "PENDING"

    fun sigLine(s: Sig) =
        "S|${s.id}|${s.ts}|${s.dir}|${s.setups.joinToString("+")}|${s.n}|${s.tf}|${s.payout}"

    fun resLine(id: Long, ts: Long, res: String) = "R|$id|$ts|$res"

    fun skipLine(ts: Long, reason: String) = "W|$ts|$reason"

    fun parse(lines: List<String>): LogData {
        val sigs = LinkedHashMap<Long, Sig>()
        val skips = LinkedHashMap<String, Int>()
        for (ln in lines) {
            val p = ln.split("|")
            try {
                when (p[0]) {
                    "S" -> {
                        val id = p[1].toLong()
                        sigs[id] = Sig(
                            id, p[2].toLong(), p[3],
                            if (p[4].isEmpty()) emptyList() else p[4].split("+"),
                            p[5].toInt(), p[6].toInt(), p[7].toInt(), PENDING
                        )
                    }
                    "R" -> sigs[p[1].toLong()]?.result = p[3]
                    "W" -> skips[p[2]] = (skips[p[2]] ?: 0) + 1
                }
            } catch (e: Exception) {
                // নষ্ট লাইন বাদ
            }
        }
        return LogData(sigs.values.toList(), skips)
    }

    fun tally(list: List<Sig>): Tally {
        var w = 0; var l = 0; var t = 0
        var profit = 0.0
        for (s in list) {
            when (s.result) {
                WIN -> { w++; profit += s.payout / 100.0 }
                LOSS -> { l++; profit -= 1.0 }
                TIE -> t++
            }
        }
        return Tally(w, l, t, profit)
    }

    /** ফলাফল দেখে সিদ্ধান্তের লেখা */
    fun verdict(t: Tally, payout: Int, minN: Int, z: Double): String {
        val be = Stats.breakEven(payout)
        if (t.n < minN) return "নমুনা কম (${t.n}/$minN): এখনই সিদ্ধান্ত নয়"
        val ci = Stats.wilson(t.wins, t.n, z)
        return when {
            ci[0] > be -> "✅ নিচের সীমা ব্রেক-ইভেনের ওপরে (তবুও ডেমো ও রিয়েল আলাদা)"
            ci[1] < be -> "❌ ব্রেক-ইভেনের নিচে: লোকসানের কৌশল"
            else -> "⚠ অনিশ্চিত: আরও নমুনা লাগবে"
        }
    }
}

/** সিগন্যালের পর কয়েক মিনিট পরের ছবি দেখে ফলাফল ঠিক করে */
object Resolver {

    const val SIG_LEN = 12

    /** সিগন্যালের সময়ের ছবি: নতুন ক্যান্ডেলের আগের ১২টির রং (1=সবুজ, 0=লাল, -1=অজানা) */
    fun signature(reading: ChartReading): IntArray {
        val cs = reading.candles
        val out = IntArray(SIG_LEN) { -1 }
        val end = cs.size - 2
        for (k in 0 until SIG_LEN) {
            val idx = end - (SIG_LEN - 1 - k)
            if (idx >= 0) {
                val c = cs[idx]
                out[k] = if (c.synthetic || c.cut) -1 else if (c.green) 1 else 0
            }
        }
        return out
    }

    /**
     * @param after সিগন্যালের ক্যান্ডেলের পর কয়টি নতুন ক্যান্ডেল এখন ছবিতে (স্বাভাবিকভাবে ২)
     * @return Pair(ফলাফল, নোট)
     */
    fun resolve(reading: ChartReading, dir: Dir, signature: IntArray, after: Int = 2): Pair<String, String> {
        if (!reading.ok) return Pair(LogModel.VOID, "চার্ট পাওয়া যায়নি")
        val cs = reading.candles
        val sigIdx = cs.size - 1 - after
        val targetIdx = sigIdx + 1
        if (after < 1 || sigIdx < 3 || targetIdx >= cs.size) return Pair(LogModel.VOID, "ক্যান্ডেল কম")

        // চার্ট বদলেছে কি না: পুরোনো ক্যান্ডেলের রং মেলানো
        var compared = 0
        var mismatch = 0
        for (k in 0 until SIG_LEN) {
            val idx = sigIdx - (SIG_LEN - k)
            if (idx < 0 || signature[k] < 0) continue
            val c = cs[idx]
            if (c.synthetic || c.cut) continue
            compared++
            val g = if (c.green) 1 else 0
            if (g != signature[k]) mismatch++
        }
        if (compared < 6) return Pair(LogModel.VOID, "মেলানোর মতো ক্যান্ডেল কম")
        if (mismatch > 1) return Pair(LogModel.VOID, "চার্ট বদলে গেছে বা ক্যান্ডেল মেলেনি ($mismatch/$compared)")

        val t = cs[targetIdx]
        if (t.synthetic) return Pair(LogModel.VOID, "লক্ষ্য ক্যান্ডেল ঢাকা ছিল")
        if (t.yBodyBottom - t.yBodyTop <= 1) return Pair(LogModel.TIE, "ক্যান্ডেল প্রায় সমান")
        val up = t.green
        val win = (dir == Dir.UP && up) || (dir == Dir.DOWN && !up)
        return Pair(if (win) LogModel.WIN else LogModel.LOSS, if (up) "পরের ক্যান্ডেল সবুজ" else "পরের ক্যান্ডেল লাল")
    }
}
