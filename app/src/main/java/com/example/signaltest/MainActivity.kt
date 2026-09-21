package com.example.signaltest

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQ_CAPTURE = 1001
        private const val REQ_NOTIF = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        val title = TextView(this).apply {
            text = "Signal Test v0.1"
            textSize = 22f
        }

        val info = TextView(this).apply {
            textSize = 16f
            setPadding(0, pad, 0, pad)
            text = "এই ভার্সন কোনো সিগন্যাল দেয় না। এটা শুধু যাচাই করে যে তোমার ট্রেডিং অ্যাপের স্ক্রিন ক্যাপচার করা যায় কি না।\n\n" +
                "ধাপ:\n" +
                "1. নিচের বোতাম চাপো। Overlay-র অনুমতি চাইলে অ্যাপটির জন্য চালু করে ব্যাক করে আবার বোতাম চাপো।\n" +
                "2. স্ক্রিন ক্যাপচারের অনুমতি চাইলে \"Entire screen\" / \"সম্পূর্ণ স্ক্রিন\" বেছে Start চাপো।\n" +
                "3. স্ক্রিনে একটি নীল 📸 বাবল আসবে। এবার Expert Option খুলে ডেমো অ্যাকাউন্টে ক্যান্ডেলস্টিক চার্টে যাও।\n" +
                "4. বাবলে একবার ট্যাপ করলে ছবি তোলা হবে ও ফলাফল বাবলে দেখাবে।\n" +
                "5. বাবল সরাতে ধরে টানো। বন্ধ করতে বাবলে ৮০০ms+ চেপে ধরে রাখো।\n\n" +
                "ছবিগুলো Gallery → Pictures → SignalTest ফোল্ডারে সেভ হয়।"
        }

        val button = Button(this).apply {
            text = "▶ শুরু করো"
            setOnClickListener { startFlow() }
        }

        root.addView(title)
        root.addView(info)
        root.addView(button)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun startFlow() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay অনুমতি চালু করে ব্যাক করো", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }

        requestCapture()
    }

    private fun requestCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) {
            // নোটিফিকেশন অনুমতি না দিলেও সার্ভিস চলবে, তাই এগিয়ে যাই
            requestCapture()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                val svc = Intent(this, CaptureService::class.java).apply {
                    putExtra("code", resultCode)
                    putExtra("data", data)
                }
                startForegroundService(svc)
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, "স্ক্রিন ক্যাপচারের অনুমতি দেওয়া হয়নি", Toast.LENGTH_LONG).show()
            }
        }
    }
}
