package com.m57.bench

import android.content.Context
import android.content.MemoryPreferences
import android.content.SharedPreferences
import com.m57.mboard.Languages
import com.m57.mboard.UserWords
import java.io.File

/**
 * Standalone micro-benchmark and parity probe for UserWords at 3k words / 5k pairs.
 * Not a unit test; lives in the desktop bench module like RerankerLatency.kt.
 */
fun main() {
    val prefs = CountingPreferences()
    val context = PrefsContext(File("../app/src/main/assets"), prefs)
    var now = 1_700_000_000_000L
    val uw = UserWords(context) { now }

    fun tag(n: Int): String {
        val sb = StringBuilder()
        var x = n
        repeat(4) {
            sb.append(('a'.code + (x % 26)).toChar())
            x /= 26
        }
        return sb.toString()
    }

    // Seed 3,000 English words (letter-only, since UserWords skips tokens with digits)
    // and 5,000 pairs across 500 previous words (10 followers each).
    val prefixes = listOf("proj", "meet", "sched", "dev", "build", "bench", "keyb", "lang", "word", "test")
    for (i in 0 until 3_000) {
        val w = "${prefixes[i % prefixes.size]}w${tag(i)}"
        val repeats = if (i % 3 == 0) 4 else 2
        repeat(repeats) { uw.record(w, null, Languages.ENGLISH) }
    }
    for (i in 0 until 5_000) {
        val prev = "prev${tag(i % 500)}"
        val next = "next${tag(i / 500)}${tag(i % 10)}"
        val repeats = if (i % 4 == 0) 4 else 2
        repeat(repeats) { uw.record(next, prev, Languages.ENGLISH) }
    }
    // Also populate Arabic so save() measures whether untouched languages/tables are re-serialized.
    repeat(3) { uw.record("مرحبا", "اهلا", Languages.ARABIC) }
    uw.reject("teh", "the", Languages.ENGLISH)
    uw.save()

    // Verify save() dirty-table writes on single-table mutations.
    prefs.puts.clear()
    uw.record("${prefixes[0]}w${tag(0)}", null, Languages.ENGLISH)
    uw.save()
    val putsAfterWordOnly = prefs.puts.toList()

    prefs.puts.clear()
    uw.reject("recieve", "receive", Languages.ENGLISH)
    uw.save()
    val putsAfterRejectOnly = prefs.puts.toList()

    // Warmup + timed micro-benchmark for next() and completions().
    val prevQueries = List(500) { "prev${tag(it)}" } + listOf("unknownprev", "missing")
    val prefixQueries = prefixes + listOf("${prefixes[0]}wa", "${prefixes[1]}wb", "zzz")

    var checksum = 0L
    val nextTimes = LongArray(10_000)
    repeat(12_000) { i ->
        val q = prevQueries[i % prevQueries.size]
        val t0 = System.nanoTime()
        val res = uw.next(q, Languages.ENGLISH)
        val dt = System.nanoTime() - t0
        checksum += res.size
        if (i >= 2_000) nextTimes[i - 2_000] = dt
    }

    val compTimes = LongArray(10_000)
    repeat(12_000) { i ->
        val q = prefixQueries[i % prefixQueries.size]
        val t0 = System.nanoTime()
        val res = uw.completions(q, Languages.ENGLISH)
        val dt = System.nanoTime() - t0
        checksum += res.size
        if (i >= 2_000) compTimes[i - 2_000] = dt
    }

    nextTimes.sort()
    compTimes.sort()

    fun fmt(arr: LongArray): String {
        val avg = arr.average() / 1_000.0
        val p50 = arr[arr.size / 2] / 1_000.0
        val p95 = arr[arr.size * 95 / 100] / 1_000.0
        val p99 = arr[arr.size * 99 / 100] / 1_000.0
        return "avg=%.2fus p50=%.2fus p95=%.2fus p99=%.2fus".format(avg, p50, p95, p99)
    }

    // Deterministic behavioral parity snapshot.
    val sampleNext = uw.next("prev${tag(0)}", Languages.ENGLISH)
    val sampleComp = uw.completions("${prefixes[0]}wa", Languages.ENGLISH)
    val knowsHit = uw.knows("${prefixes[0]}w${tag(0)}", Languages.ENGLISH)
    val knowsMiss = uw.knows("nonexistent", Languages.ENGLISH)
    val rejectsHit = uw.rejects("teh", "the", Languages.ENGLISH)
    val liveSize = uw.size()

    // Reload from same SharedPreferences after 14 days to verify backward-compatible storage + decay.
    val day14 = 14L * 24 * 60 * 60 * 1000
    now += day14
    val reloaded = UserWords(context) { now }
    val reloadedNext = reloaded.next("prev${tag(0)}", Languages.ENGLISH)
    val reloadedComp = reloaded.completions("${prefixes[0]}wa", Languages.ENGLISH)
    val reloadedSize = reloaded.size()
    val reloadedRejects = reloaded.rejects("teh", "the", Languages.ENGLISH)
    reloaded.acceptCorrection("teh", "the", Languages.ENGLISH)
    val afterAcceptRejects = reloaded.rejects("teh", "the", Languages.ENGLISH)

    // Verify cap pruning stays in sync when exceeding LIMIT / PAIR_LIMIT.
    repeat(20) { i ->
        reloaded.record("extra${tag(i)}", "prev${tag(0)}", Languages.ENGLISH)
    }
    val afterPruneSize = reloaded.size()

    println("seeded_size=$liveSize reloaded_size=$reloadedSize after_prune_size=$afterPruneSize")
    println("next_5k: ${fmt(nextTimes)}")
    println("completions_3k: ${fmt(compTimes)}")
    println("save_puts_word_only=$putsAfterWordOnly")
    println("save_puts_reject_only=$putsAfterRejectOnly")
    println("sample_next=$sampleNext")
    println("sample_comp=$sampleComp")
    println("knows=$knowsHit/$knowsMiss rejects=$rejectsHit->$reloadedRejects->$afterAcceptRejects")
    println("reloaded_next=$reloadedNext")
    println("reloaded_comp=$reloadedComp")
    println("checksum=$checksum")
}

private class PrefsContext(
    assetsDir: File,
    private val shared: SharedPreferences,
) : Context(assetsDir) {
    override fun getSharedPreferences(
        name: String,
        mode: Int,
    ): SharedPreferences = shared
}

private class CountingPreferences : SharedPreferences {
    private val delegate = MemoryPreferences()
    val puts = ArrayList<String>()

    override fun getString(
        key: String,
        defValue: String?,
    ) = delegate.getString(key, defValue)

    override fun getBoolean(
        key: String,
        defValue: Boolean,
    ) = delegate.getBoolean(key, defValue)

    override fun edit(): SharedPreferences.Editor {
        val inner = delegate.edit()
        return object : SharedPreferences.Editor {
            override fun putString(
                key: String,
                value: String?,
            ) = apply {
                puts.add(key)
                inner.putString(key, value)
            }

            override fun putBoolean(
                key: String,
                value: Boolean,
            ) = apply {
                puts.add(key)
                inner.putBoolean(key, value)
            }

            override fun clear() = apply { inner.clear() }

            override fun apply() {
                inner.apply()
            }
        }
    }
}
