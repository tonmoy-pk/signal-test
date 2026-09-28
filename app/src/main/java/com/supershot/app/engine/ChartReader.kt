package com.supershot.app.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * স্ক্রিনশট (ARGB পিক্সেল) থেকে ক্যান্ডেল বের করে।
 * Android-এর ওপর নির্ভর করে না, তাই কম্পিউটারেও পরীক্ষা করা যায়।
 */
object ChartReader {

    private class Comp(
        val x0: Int, val x1: Int, val y0: Int, val y1: Int,
        val color: Int, val area: Int
    ) {
        val w: Int get() = x1 - x0
        val cx: Int get() = (x0 + x1) / 2
    }

    private class Grp(
        var x0: Int, var x1: Int, var y0: Int, var y1: Int,
        var color: Int, var bestArea: Int
    ) {
        fun add(c: Comp) {
            x0 = min(x0, c.x0); x1 = max(x1, c.x1)
            y0 = min(y0, c.y0); y1 = max(y1, c.y1)
            if (c.area > bestArea) { bestArea = c.area; color = c.color }
        }
    }

    private fun near(r: Int, g: Int, b: Int, c: IntArray, tol: Int): Boolean =
        abs(r - c[0]) <= tol && abs(g - c[1]) <= tol && abs(b - c[2]) <= tol

    private fun fail(msg: String, roiTop: Int = 0, roiBottom: Int = 0) = ChartReading(
        ok = false, message = msg, candles = emptyList(), allCandles = emptyList(),
        bodyWidth = 0, pitch = 0, priceLineY = -1, roiTop = roiTop, roiBottom = roiBottom, gaps = 0
    )

    fun read(px: IntArray, w: Int, h: Int, prof: Profile = Profile.EXPERT_OPTION): ChartReading {
        val roiTop = (h * prof.roiTop).toInt()
        val roiBottom = (h * prof.roiBottom).toInt()
        val rh = roiBottom - roiTop
        if (w <= 0 || rh <= 0 || px.size < w * h) return fail("ছবির মাপ ঠিক নেই")

        // ---------- ১. রং ভাগ করা ----------
        val mask = ByteArray(w * rh)
        val orangeCount = IntArray(w)
        val blueRow = IntArray(rh)
        var masked = 0
        for (y in 0 until rh) {
            val rowBase = (roiTop + y) * w
            val mBase = y * w
            for (x in 0 until w) {
                val p = px[rowBase + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (near(r, g, b, prof.green, prof.tol)) {
                    mask[mBase + x] = 1; masked++
                } else if (near(r, g, b, prof.red, prof.tol)) {
                    mask[mBase + x] = 2; masked++
                } else if (near(r, g, b, prof.orange, prof.tol + 4)) {
                    orangeCount[x]++
                } else if (near(r, g, b, prof.blue, prof.tol + 4)) {
                    blueRow[y]++
                }
            }
        }
        if (masked < 200) return fail("চার্টের ক্যান্ডেল পাওয়া যায়নি", roiTop, roiBottom)

        // কমলা উল্লম্ব রেখা (ট্রেডের সময়-রেখা) যেসব কলামে আছে, সেখানকার ফাঁক ভরাট
        val orangeCols = BooleanArray(w)
        for (x in 0 until w) if (orangeCount[x] >= rh * 0.5) orangeCols[x] = true
        for (x in 0 until w) {
            if (!orangeCols[x]) continue
            var xl = x - 1
            while (xl >= 0 && orangeCols[xl]) xl--
            var xr = x + 1
            while (xr < w && orangeCols[xr]) xr++
            if (xl < 0 || xr >= w) continue
            for (y in 0 until rh) {
                val a = mask[y * w + xl]
                if (a.toInt() != 0 && a == mask[y * w + xr]) mask[y * w + x] = a
            }
        }

        // নীল দামের রেখা: বর্তমান দামের সারি
        var lineSum = 0
        var lineCnt = 0
        for (y in 0 until rh) {
            if (blueRow[y] >= w * 0.45) { lineSum += y; lineCnt++ }
        }
        val priceLineY = if (lineCnt > 0) roiTop + lineSum / lineCnt else -1

        // ---------- ২. উল্লম্ব ফাঁক ভরাট (দামের রেখা যে ক্যান্ডেল কেটেছে) ----------
        for (x in 0 until w) {
            var last = 0
            var lastY = -100
            for (y in 0 until rh) {
                val c = mask[y * w + x].toInt()
                if (c != 0) {
                    val gap = y - lastY - 1
                    if (c == last && gap in 1..prof.closeGap) {
                        for (k in lastY + 1 until y) mask[k * w + x] = c.toByte()
                    }
                    last = c
                    lastY = y
                }
            }
        }

        // ---------- ৩. সংযুক্ত অংশ (connected components) ----------
        var maskedNow = 0
        for (i in mask.indices) if (mask[i].toInt() != 0) maskedNow++
        val done = BooleanArray(w * rh)
        val stack = IntArray(maskedNow + 1)
        val comps = ArrayList<Comp>()
        for (start in 0 until w * rh) {
            val c = mask[start].toInt()
            if (c == 0 || done[start]) continue
            var sp = 0
            stack[sp++] = start
            done[start] = true
            var minX = w; var maxX = -1; var minY = rh; var maxY = -1; var area = 0
            while (sp > 0) {
                val cur = stack[--sp]
                val cy = cur / w
                val cx = cur - cy * w
                area++
                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy
                for (dy in -1..1) {
                    val ny = cy + dy
                    if (ny < 0 || ny >= rh) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = cx + dx
                        if (nx < 0 || nx >= w) continue
                        val ni = ny * w + nx
                        if (!done[ni] && mask[ni].toInt() == c) {
                            done[ni] = true
                            stack[sp++] = ni
                        }
                    }
                }
            }
            comps.add(Comp(minX, maxX + 1, minY, maxY + 1, c, area))
        }

        // ---------- ৪. ক্যান্ডেলের সাধারণ প্রস্থ ----------
        val hist = IntArray(200)
        for (c in comps) {
            if (c.area >= 60 && c.w in 8..150) hist[c.w]++
        }
        var bw = 0
        var bestScore = 0
        for (i in 8 until 150) {
            val s = hist[i - 1] + hist[i] + hist[i + 1]
            if (s > bestScore) { bestScore = s; bw = i }
        }
        if (bw == 0) return fail("ক্যান্ডেলের আকার বোঝা যায়নি", roiTop, roiBottom)
        val bwLo = bw - 3
        val bwHi = bw + 3

        // ---------- ৫. গ্রুপ: একটি ক্যান্ডেলের টুকরোগুলো এক করা ----------
        val kept = comps.filter { it.w >= 2 && (it.area >= 24 || it.w >= bwLo) }
        val anchors = kept.filter { it.w in bwLo..bwHi }.sortedBy { it.x0 }
        val groups = ArrayList<Grp>()
        for (a in anchors) {
            var merged = false
            for (g in groups.asReversed()) {
                val overlap = min(g.x1, a.x1) - max(g.x0, a.x0)
                if (overlap >= bw * 0.6) { g.add(a); merged = true; break }
                if (g.x1 < a.x0 - bw) break
            }
            if (!merged) groups.add(Grp(a.x0, a.x1, a.y0, a.y1, a.color, a.area))
        }
        // সরু টুকরো (উইক/আংশিক) যেগুলো কোনো গ্রুপের ভেতরে পড়ে
        val leftovers = ArrayList<Comp>()
        for (c in kept) {
            if (c.w in bwLo..bwHi) continue
            if (c.w > bwHi) continue
            var placed = false
            for (g in groups) {
                if (c.color == g.color && c.cx >= g.x0 - 1 && c.cx <= g.x1 + 1) {
                    g.y0 = min(g.y0, c.y0); g.y1 = max(g.y1, c.y1)
                    placed = true
                    break
                }
            }
            if (!placed) leftovers.add(c)
        }
        // বাকি টুকরো নিজেদের মধ্যে জোড়া লাগাই (যেমন বোতামে দুই ভাগ হওয়া ক্যান্ডেল)
        leftovers.sortBy { it.x0 }
        val extra = ArrayList<Grp>()
        for (c in leftovers) {
            var merged = false
            for (g in extra.asReversed()) {
                if (c.color == g.color && c.x0 <= g.x1 + 1 && c.x1 >= g.x0 - 1) {
                    g.add(c); merged = true; break
                }
                if (g.x1 < c.x0 - bw) break
            }
            if (!merged) extra.add(Grp(c.x0, c.x1, c.y0, c.y1, c.color, c.area))
        }
        groups.addAll(extra)

        // ---------- ৬. ঢাকা পড়া জায়গার তালিকা (পিক্সেলে) ----------
        val rects = ArrayList<IntArray>()
        for (o in prof.occluders) {
            rects.add(intArrayOf((o[0] * w).toInt(), (o[1] * h).toInt(), (o[2] * w).toInt(), (o[3] * h).toInt()))
        }
        if (priceLineY >= 0) {
            rects.add(intArrayOf(0, priceLineY - 50, (0.37 * w).toInt(), priceLineY + 62))
        }

        // ---------- ৭. প্রতিটি গ্রুপ থেকে ক্যান্ডেল ----------
        val candles = ArrayList<Candle>()
        val thr = (bw * 0.7).toInt().coerceAtLeast(2)
        for (g in groups.sortedBy { it.x0 }) {
            val gw = g.x1 - g.x0
            if (gw < bwLo || gw > bw + 4) continue
            var top = -1
            var bottom = -1
            for (y in g.y0 until g.y1) {
                var cnt = 0
                val base = y * w
                for (x in g.x0 until g.x1) if (mask[base + x].toInt() == g.color) cnt++
                if (cnt >= thr) {
                    if (top < 0) top = y
                    bottom = y + 1
                }
            }
            var cutFlag: Boolean
            val yHigh = roiTop + g.y0
            val yLow = roiTop + g.y1
            val bTop: Int
            val bBottom: Int
            if (top < 0) {
                val mid = (yHigh + yLow) / 2
                bTop = mid; bBottom = mid + 1; cutFlag = true
            } else {
                bTop = roiTop + top; bBottom = roiTop + bottom
                cutFlag = false
            }
            if (isCut(g.x0, g.x1, yHigh, yLow, rects, w)) cutFlag = true
            candles.add(Candle(g.x0, g.x1, yHigh, yLow, bTop, bBottom, g.color == 1, cutFlag))
        }
        candles.sortBy { it.cx }

        if (candles.size < 8) {
            return ChartReading(
                false, "ক্যান্ডেল কম পাওয়া গেছে (${candles.size})", candles, candles,
                bw, 0, priceLineY, roiTop, roiBottom, 0
            )
        }

        // ---------- ৮. দূরত্ব (pitch) ও ধারাবাহিক উইন্ডো ----------
        val diffs = ArrayList<Int>()
        for (i in 1 until candles.size) {
            val d = candles[i].cx - candles[i - 1].cx
            if (d >= bw && d <= (bw * 1.5).toInt() + 2) diffs.add(d)
        }
        diffs.sort()
        val pitch = if (diffs.isEmpty()) bw + 3 else diffs[diffs.size / 2]

        // নতুন থেকে পুরোনোর দিকে যাই। আইকনে ঢাকা ছোট ফাঁক (৩টি পর্যন্ত) বানিয়ে ভরি, বড় ফাঁকে থামি।
        val newestFirst = ArrayList<Candle>()
        newestFirst.add(candles[candles.size - 1])
        var filled = 0
        var i = candles.size - 1
        while (i > 0) {
            val next = candles[i]
            val prev = candles[i - 1]
            val d = next.cx - prev.cx
            if (d < pitch * 0.6) break
            val missing = Math.round(d.toDouble() / pitch).toInt() - 1
            if (missing > 3 || filled + missing > 8) break
            if (missing > 0) {
                val yNext = closeY(next)
                val yPrev = closeY(prev)
                for (j in 1..missing) {
                    val y = yNext + (yPrev - yNext) * j / (missing + 1)
                    val cx = next.cx - j * pitch
                    newestFirst.add(
                        Candle(cx - bw / 2, cx - bw / 2 + bw, y, y, y, y + 1, true, true, true)
                    )
                }
                filled += missing
            }
            newestFirst.add(prev)
            i--
        }
        val window = newestFirst.asReversed().toList()
        val gaps = filled

        return ChartReading(
            true, "OK", window, candles, bw, pitch, priceLineY, roiTop, roiBottom, gaps
        )
    }

    private fun closeY(k: Candle): Int = if (k.green) k.yBodyTop else k.yBodyBottom

    private fun isCut(x0: Int, x1: Int, yHigh: Int, yLow: Int, rects: List<IntArray>, w: Int): Boolean {
        if (x0 <= 0 || x1 >= w) return true
        for (r in rects) {
            if (x1 <= r[0] || x0 >= r[2]) continue
            if (yHigh >= r[1] - 2 && yHigh <= r[3] + 2) return true
            if (yLow >= r[1] - 2 && yLow <= r[3] + 2) return true
        }
        return false
    }

    /** ক্যান্ডেল → OHLC (দাম = -y, ওপরে গেলে দাম বাড়ে) */
    fun toSeries(candles: List<Candle>): Series {
        val n = candles.size
        val o = DoubleArray(n)
        val h = DoubleArray(n)
        val l = DoubleArray(n)
        val c = DoubleArray(n)
        for (i in 0 until n) {
            val k = candles[i]
            h[i] = -k.yHigh.toDouble()
            l[i] = -k.yLow.toDouble()
            if (k.green) {
                o[i] = -k.yBodyBottom.toDouble()
                c[i] = -k.yBodyTop.toDouble()
            } else {
                o[i] = -k.yBodyTop.toDouble()
                c[i] = -k.yBodyBottom.toDouble()
            }
            if (h[i] < max(o[i], c[i])) h[i] = max(o[i], c[i])
            if (l[i] > min(o[i], c[i])) l[i] = min(o[i], c[i])
        }
        return Series(o, h, l, c)
    }
}
