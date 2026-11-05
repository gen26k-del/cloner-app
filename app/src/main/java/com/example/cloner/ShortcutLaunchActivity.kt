package com.example.cloner

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// হোম-স্ক্রিন শর্টকাটের সরাসরি লঞ্চার: স্বচ্ছ, UI দেখায় না,
// ক্লোন চালু করেই নিজে বন্ধ হয়ে যায়।
class ShortcutLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra("clone_pkg")
        val userId = intent.getIntExtra("clone_user", 0)
        if (pkg.isNullOrEmpty()) {
            finish()
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val result = Engine.current.launchClone(pkg, userId)
            if (result.isFailure) {
                val msg = result.exceptionOrNull()?.message ?: "চালু করা যায়নি"
                runOnUiThread {
                    Toast.makeText(this@ShortcutLaunchActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
            // launchApk সিস্টেমকে stub activity চালু করতে বলে দিয়েছে;
            // trampoline-এর আর দরকার নেই
            runOnUiThread { finish() }
        }
    }
}
