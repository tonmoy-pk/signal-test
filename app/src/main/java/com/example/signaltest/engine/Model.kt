package com.example.signaltest.engine

enum class Dir { UP, DOWN }

/** ছবিতে শনাক্ত হওয়া একটি ক্যান্ডেল। y-মান পিক্সেলে (ওপরে ছোট), শেষ প্রান্ত exclusive। */
data class Candle(
    val x0: Int,
    val x1: Int,
    val yHigh: Int,
    val yLow: Int,
    val yBodyTop: Int,
    val yBodyBottom: Int,
    val green: Boolean,
    val cut: Boolean,
    /** আইকনের নিচে ঢাকা পড়ায় ছবিতে নেই; দুই পাশের ক্যান্ডেল থেকে বানানো */
    val synthetic: Boolean = false
) {
    val cx: Int get() = (x0 + x1) / 2
}

data class ChartReading(
    val ok: Boolean,
    val message: String,
    /** নির্ভরযোগ্য উইন্ডো: ধারাবাহিক ক্যান্ডেল, পুরোনো → নতুন */
    val candles: List<Candle>,
    /** শনাক্ত সব ক্যান্ডেল (ডিবাগ আঁকার জন্য) */
    val allCandles: List<Candle>,
    val bodyWidth: Int,
    val pitch: Int,
    val priceLineY: Int,
    val roiTop: Int,
    val roiBottom: Int,
    /** ঢাকা পড়া কতগুলো ক্যান্ডেল বানিয়ে ভরা হয়েছে */
    val gaps: Int
)

class Series(
    val o: DoubleArray,
    val h: DoubleArray,
    val l: DoubleArray,
    val c: DoubleArray
) {
    val n: Int get() = c.size
}

data class Setup(val id: String, val dir: Dir, val text: String)

data class Analysis(
    val ok: Boolean,
    val signal: Dir?,
    val setups: List<Setup>,
    /** WAIT-এর কারণ বা "OK" */
    val reason: String,
    val candleCount: Int,
    val info: String
)

/** ব্রোকারের চার্ট থিমের ক্যালিব্রেশন। Expert Option-এর ছবি থেকে মাপা। */
class Profile(
    val green: IntArray,
    val red: IntArray,
    val orange: IntArray,
    val blue: IntArray,
    val tol: Int,
    val roiTop: Double,
    val roiBottom: Double,
    val closeGap: Int,
    /** [x0, y0, x1, y1] স্ক্রিনের ভগ্নাংশে: চার্টের ওপর বসা বোতাম */
    val occluders: List<DoubleArray>
) {
    companion object {
        val EXPERT_OPTION = Profile(
            green = intArrayOf(14, 143, 94),
            red = intArrayOf(198, 69, 71),
            orange = intArrayOf(247, 167, 65),
            blue = intArrayOf(53, 167, 255),
            tol = 12,
            roiTop = 0.185,
            roiBottom = 0.79,
            closeGap = 5,
            occluders = listOf(
                doubleArrayOf(0.060, 0.198, 0.160, 0.242), // বাঁ-ওপরের বোতাম
                doubleArrayOf(0.060, 0.678, 0.160, 0.722), // রোবট বোতাম
                doubleArrayOf(0.060, 0.738, 0.160, 0.782), // টিউনার বোতাম
                doubleArrayOf(0.832, 0.460, 0.918, 0.500), // ডান চেভরন
                doubleArrayOf(0.840, 0.738, 0.936, 0.782)  // ডান-নিচের বোতাম
            )
        )
    }
}
