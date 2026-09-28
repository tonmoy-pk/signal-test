package com.supershot.app.engine

/** লগ: সিগন্যাল, ফলাফল ও স্কিপ। Android-মুক্ত, তাই আলাদাভাবে পরীক্ষা করা যায়। */
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

class MoveRec(val ts: Long, val move: String, val tf: Int)

class LogData(val signals: List<Sig>, val skips: Map<String, Int>, val moves: List<MoveRec> = emptyList())

/** সব মিনিটের দামের চলন (সিগন্যাল নির্বিশেষে): তুলনার ভিত্তি */
class Baseline(val up: Int, val down: Int, val tie: Int, val pairs: Int, val same: Int) {
    val n: Int get() = up + down
}

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

    fun moveLine(ts: Long, move: String, tf: Int) = "M|$ts|$move|$tf"

    fun parse(lines: List<String>): LogData {
        val sigs = LinkedHashMap<Long, Sig>()
        val skips = LinkedHashMap<String, Int>()
        val moves = ArrayList<MoveRec>()
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
                    "M" -> moves.add(MoveRec(p[1].toLong(), p[2], p[3].toInt()))
                }
            } catch (e: Exception) {
                // নষ্ট লাইন বাদ
            }
        }
        return LogData(sigs.values.toList(), skips, moves)
    }

    fun baseline(moves: List<MoveRec>): Baseline {
        var up = 0; var down = 0; var tie = 0
        var pairs = 0; var same = 0
        for ((i, m) in moves.withIndex()) {
            when (m.move) { "UP" -> up++; "DOWN" -> down++; else -> tie++ }
            if (i > 0) {
                val a = moves[i - 1]
                val consecutive = m.ts - a.ts <= a.tf * 60_000L * 1.25 && m.tf == a.tf
                if (consecutive && a.move != "TIE" && m.move != "TIE") {
                    pairs++
                    if (a.move == m.move) same++
                }
            }
        }
        return Baseline(up, down, tie, pairs, same)
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

    /** ফলাফল দেখে সিদ্ধান্ত। লেখা UI-তে বসে (ভাষা অনুযায়ী), এখানে শুধু ধরন */
    fun verdict(t: Tally, payout: Int, minN: Int, z: Double): Verdict {
        val be = Stats.breakEven(payout)
        if (t.n < minN) return Verdict(VerdictKind.FEW_SAMPLES, t.n, minN)
        val ci = Stats.wilson(t.wins, t.n, z)
        return when {
            ci[0] > be -> Verdict(VerdictKind.ABOVE_BREAK_EVEN, t.n, minN)
            ci[1] < be -> Verdict(VerdictKind.BELOW_BREAK_EVEN, t.n, minN)
            else -> Verdict(VerdictKind.UNCERTAIN, t.n, minN)
        }
    }
}

enum class VerdictKind { FEW_SAMPLES, ABOVE_BREAK_EVEN, BELOW_BREAK_EVEN, UNCERTAIN }

class Verdict(val kind: VerdictKind, val n: Int, val minN: Int)

/** সিগন্যালের মুহূর্তের ছবির তথ্য: এন্ট্রি-দাম ও আগের ১২টি সম্পূর্ণ ক্যান্ডেলের অবস্থান */
class EntrySnap(val cycle: Long, val tsMs: Long, val entryY: Int, val refs: IntArray)

/**
 * আসল ১ মিনিটের ট্রেডের মতো ফলাফল: এন্ট্রির সময়ের দাম বনাম ঠিক এক ক্যান্ডেল-সময় পরের দাম।
 * দুই ছবির y-স্কেল বদলে গেলেও আগের সম্পূর্ণ ক্যান্ডেলগুলো মিলিয়ে সমীকরণ বের করে দাম তুলনা করা হয়।
 */
object Resolver {

    const val REF_N = 12

    private fun closeY(c: Candle): Int = if (c.green) c.yBodyTop else c.yBodyBottom

    fun snapshot(reading: ChartReading, cycle: Long, tsMs: Long): EntrySnap? {
        if (!reading.ok) return null
        val cs = reading.candles
        val n = cs.size
        if (n < REF_N + 2) return null
        val last = cs[n - 1]
        if (last.cut || last.synthetic) return null
        val refs = IntArray(REF_N * 4) { -1 }
        for (j in 1..REF_N) {
            val c = cs[n - 1 - j]
            if (c.cut || c.synthetic) continue
            val b = (j - 1) * 4
            refs[b] = c.yHigh; refs[b + 1] = c.yLow; refs[b + 2] = c.yBodyTop; refs[b + 3] = c.yBodyBottom
        }
        return EntrySnap(cycle, tsMs, closeY(last), refs)
    }

    /** এক ক্যান্ডেল পরের ছবি থেকে দামের চলন: UP / DOWN / TIE / VOID (সাথে নোট) */
    fun move(snap: EntrySnap, reading: ChartReading): Pair<String, String> {
        if (!reading.ok) return Pair(LogModel.VOID, "chart not found")
        val cs = reading.candles
        val m = cs.size
        if (m < 8) return Pair(LogModel.VOID, "too few candles")
        val last = cs[m - 1]
        if (last.cut || last.synthetic) return Pair(LogModel.VOID, "last candle covered")

        var cnt = 0
        val xs = DoubleArray(REF_N * 2)
        val ys = DoubleArray(REF_N * 2)
        for (j in 1..REF_N) {
            val idx = m - 2 - j
            if (idx < 0) continue
            val e = cs[idx]
            if (e.cut || e.synthetic) continue
            val b = (j - 1) * 4
            if (snap.refs[b] < 0) continue
            xs[cnt] = snap.refs[b + 2].toDouble(); ys[cnt] = e.yBodyTop.toDouble(); cnt++
            xs[cnt] = snap.refs[b + 3].toDouble(); ys[cnt] = e.yBodyBottom.toDouble(); cnt++
        }
        if (cnt < 16) return Pair(LogModel.VOID, "too few reference candles")

        // দুই ছবির y-স্কেল মেলানো: জোড়া-জোড়া বাছাই (RANSAC), যাতে ওপরে বসা লেবেলে ঢাকা ক্যান্ডেল ফলাফল না বিগড়ায়
        var bestIn = -1
        var bestA = 1.0
        var bestB = 0.0
        for (p in 0 until cnt) {
            for (q in p + 1 until cnt) {
                val dx = xs[q] - xs[p]
                if (Math.abs(dx) < 20) continue
                val a = (ys[q] - ys[p]) / dx
                if (a < 0.4 || a > 2.5) continue
                val b = ys[p] - a * xs[p]
                var inl = 0
                for (t in 0 until cnt) if (Math.abs(ys[t] - (a * xs[t] + b)) <= 2.5) inl++
                if (inl > bestIn) { bestIn = inl; bestA = a; bestB = b }
            }
        }
        if (bestIn < 0) return Pair(LogModel.VOID, "no scale match")
        // সেরা সমীকরণের ইনলাইয়ার দিয়ে পুনরায় ফিট
        var n2 = 0
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (t in 0 until cnt) {
            if (Math.abs(ys[t] - (bestA * xs[t] + bestB)) <= 2.5) {
                n2++; sx += xs[t]; sy += ys[t]; sxx += xs[t] * xs[t]; sxy += xs[t] * ys[t]
            }
        }
        val den = n2 * sxx - sx * sx
        if (n2 < 12 || den < 1.0 || n2 < 0.7 * cnt) {
            return Pair(LogModel.VOID, "chart changed or candles mismatch ($n2/$cnt matched)")
        }
        val a = (n2 * sxy - sx * sy) / den
        val b0 = (sy - a * sx) / n2
        var sq = 0.0
        var used = 0
        for (t in 0 until cnt) {
            val d = ys[t] - (a * xs[t] + b0)
            if (Math.abs(d) <= 2.5) { sq += d * d; used++ }
        }
        val rms = Math.sqrt(sq / used)
        if (rms > 2.0 || a < 0.4 || a > 2.5) {
            return Pair(LogModel.VOID, "chart mismatch (rms ${"%.1f".format(rms)})")
        }
        val mapped = a * snap.entryY + b0
        val d = mapped - closeY(last)   // ধনাত্মক = এখন দাম বেশি
        return when {
            Math.abs(d) <= 1.0 -> Pair("TIE", "price about equal")
            d > 0 -> Pair("UP", "price up")
            else -> Pair("DOWN", "price down")
        }
    }

    fun outcome(dir: Dir, move: String): String = when {
        move == "UP" && dir == Dir.UP -> LogModel.WIN
        move == "DOWN" && dir == Dir.DOWN -> LogModel.WIN
        move == "UP" || move == "DOWN" -> LogModel.LOSS
        move == "TIE" -> LogModel.TIE
        else -> LogModel.VOID
    }
}
