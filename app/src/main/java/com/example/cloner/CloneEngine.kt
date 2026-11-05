package com.example.cloner

/**
 * একটা ক্লোনের পরিচয়: প্যাকেজ + ভার্চুয়াল ইউজার।
 * একই অ্যাপের একাধিক ক্লোন = একই pkg, আলাদা userId (আলাদা ডেটা)।
 */
data class CloneId(val pkg: String, val userId: Int) {
    override fun toString(): String = "$pkg:$userId"

    companion object {
        /** "pkg:userId" বা পুরনো "pkg" ফরম্যাট থেকে পার্স করে */
        fun parse(s: String): CloneId? = try {
            val i = s.lastIndexOf(':')
            if (i < 0) {
                CloneId(s, 0) // পুরনো ফরম্যাট
            } else {
                CloneId(s.substring(0, i), s.substring(i + 1).toInt())
            }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * ভার্চুয়ালাইজেশন ইঞ্জিনের সংযোগস্থল।
 * আসল ইঞ্জিন (যেমন BlackBox-ভিত্তিক) যুক্ত করার পর
 * এই ইন্টারফেস ইমপ্লিমেন্ট করে Engine.current-এ বসাতে হবে।
 */
interface CloneEngine {
    val name: String
    fun isReady(): Boolean
    fun installClone(packageName: String, userId: Int = 0): Result<Unit>
    fun launchClone(packageName: String, userId: Int = 0): Result<Unit>
    fun uninstallClone(packageName: String, userId: Int = 0): Result<Unit> =
        Result.failure(UnsupportedOperationException("এই ইঞ্জিনে আনইনস্টল সাপোর্ট নেই"))
    fun listClones(): List<CloneId>
    /** এই প্যাকেজের পরের ফাঁকা userId; জায়গা না থাকলে -1 */
    fun nextUserId(packageName: String): Int = 0
}

/** ইঞ্জিন যুক্ত না থাকলে এটি ব্যবহার হয়। ক্লোন আসলে কাজ করবে না। */
object NoEngine : CloneEngine {
    override val name = "কোনো ইঞ্জিন নেই"
    override fun isReady() = false
    override fun installClone(packageName: String, userId: Int): Result<Unit> =
        Result.failure(IllegalStateException("ভার্চুয়ালাইজেশন ইঞ্জিন এখনো যুক্ত করা হয়নি"))
    override fun launchClone(packageName: String, userId: Int): Result<Unit> =
        Result.failure(IllegalStateException("ভার্চুয়ালাইজেশন ইঞ্জিন এখনো যুক্ত করা হয়নি"))
    override fun listClones(): List<CloneId> = emptyList()
}

object Engine {
    var current: CloneEngine = NoEngine

}
