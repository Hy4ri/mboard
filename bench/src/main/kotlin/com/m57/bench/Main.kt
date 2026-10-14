package com.m57.bench

import android.content.Context
import com.m57.mboard.Languages
import com.m57.mboard.WordDictionary
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Replays text through the keyboard's suggestion code and scores it.
 *
 *   --lang en|ar  --sentences FILE  --typos FILE  --dialect  --learning  --limit N  --label NAME  --out DIR
 *
 * Sentences are typed letter by letter; before each letter the strip is read. A user who taps the
 * word as soon as it shows spends (letters typed + 1 tap); typing it out costs (letters + space).
 */
fun main(args: Array<String>) {
    val opts = parse(args)
    val language = Languages.byCode(opts["lang"] ?: "en")
    val assets = File(opts["assets"] ?: "../app/src/main/assets")
    val dialect = "dialect" in opts
    val learning = "learning" in opts
    val limit = opts["limit"]?.toInt() ?: Int.MAX_VALUE
    val label = opts["label"] ?: "run"
    val out = File(opts["out"] ?: "results").also { it.mkdirs() }

    val started = System.nanoTime()
    val strip = Strip(Context(assets), language, dialect, learning)
    val loadMs = (System.nanoTime() - started) / 1_000_000

    val report = JSONObject()
    report.put("label", label)
    report.put("lang", language.code)
    report.put("dialect", dialect)
    report.put("learning", learning)
    report.put("dictionary_load_ms", loadMs)

    opts["sentences"]?.let { path ->
        val lines = File(path).readLines().filter { it.isNotBlank() }.take(limit)
        report.put("sentences", SentenceReplay(strip).run(lines, File(out, "$label.misses.tsv")))
        report.put("sentences_file", path)
    }
    opts["typos"]?.let { path ->
        val rows =
            File(path)
                .readLines()
                .drop(1)
                .filter { it.isNotBlank() }
                .take(limit)
        val classification = File(opts["classification-words"] ?: "lm/seeds/words_${language.code}.tsv")
        val dictionary = classification.bufferedReader().use { WordDictionary.read(it, language) }
        report.put("typos", TypoReplay(strip, dictionary).run(rows, File(out, "$label.typos.tsv")))
        report.put("typos_file", path)
    }
    File(out, "$label.json").writeText(report.toString(2))
    println(report.toString(2))
    // The dictionary loader thread is not a daemon; close it so the JVM exits.
    strip.words.close()
    kotlin.system.exitProcess(0)
}

private fun parse(args: Array<String>): Map<String, String> {
    val map = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val key = args[i].removePrefix("--")
        if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
            map[key] = args[i + 1]
            i += 2
        } else {
            map[key] = "true"
            i++
        }
    }
    return map
}

private fun same(
    a: String,
    b: String,
) = a.lowercase(Locale.ROOT) == b.lowercase(Locale.ROOT)

/** Arabic spellings people treat as one word: hamza seats on alef, final ى/ي, final ة/ه. */
private fun fold(word: String) =
    word
        .lowercase(Locale.ROOT)
        .replace(Regex("[أإآٱ]"), "ا")
        .replace(Regex("ى$"), "ي")
        .replace(Regex("ة$"), "ه")

/** Latency of one strip render, in microseconds. */
class Timer {
    private val samples = ArrayList<Long>()

    fun <T> time(block: () -> T): T {
        val t = System.nanoTime()
        val result = block()
        samples += (System.nanoTime() - t) / 1000
        return result
    }

    fun json(): JSONObject {
        val s = samples.sorted()
        if (s.isEmpty()) return JSONObject()

        fun pct(p: Double) = s[((s.size - 1) * p).toInt()]
        return JSONObject()
            .put("renders", s.size)
            .put("p50_us", pct(0.5))
            .put("p95_us", pct(0.95))
            .put("p99_us", pct(0.99))
            .put("max_us", s.last())
    }
}

class SentenceReplay(
    private val strip: Strip,
) {
    private val timer = Timer()

    fun run(
        lines: List<String>,
        missFile: File,
    ): JSONObject {
        var words = 0
        var predictTop1 = 0
        var predictTop3 = 0
        var predictWords = 0
        var firstTop3 = 0
        var firstWords = 0
        var completionTop1 = 0
        var completionTop3 = 0
        var completionWords = 0
        var keysWithout = 0L
        var keysWith = 0L
        var lettersToFind = 0L
        var found = 0
        var neverFound = 0
        var falseCorrections = 0
        var spellingNormalized = 0
        var autoCorrectionChecks = 0
        val byLength = HashMap<Int, IntArray>() // length bucket -> [words, found within 2 letters]
        val misses = StringBuilder("context\ttarget\tshown_after_full_word\tfalse_correction\n")

        for (line in lines) {
            // Tokens: words are runs of word characters; everything else is typed as-is.
            val tokens = Regex("[\\p{L}\\p{N}\\p{Mn}']+|[^\\p{L}\\p{N}\\p{Mn}']+").findAll(line).map { it.value }.toList()
            var text = ""
            for (token in tokens) {
                if (!strip.wordChar(token[0])) {
                    text += token
                    continue
                }
                val scored = token.none(Char::isDigit) && token.trim('\'').isNotEmpty()
                if (!scored) {
                    text += token
                    continue
                }
                words++
                val first = strip.context(text).contextWords.isEmpty()
                var foundAt = -1
                var lastShown: Strip.Shown? = null
                for (k in 0..token.length) {
                    val typed = text + token.substring(0, k)
                    val c = strip.context(typed)
                    if (k == 0 && !c.idle) break // glued to a previous token: no prediction slot
                    val shown = timer.time { strip.show(c) }
                    lastShown = shown
                    val hit = shown.values.indexOfFirst { same(it, token) }
                    if (k == 0) {
                        if (first) {
                            firstWords++
                            if (hit >= 0) firstTop3++
                        } else {
                            predictWords++
                            if (hit == 0) predictTop1++
                            if (hit >= 0) predictTop3++
                        }
                    }
                    if (k == 1) {
                        completionWords++
                        if (hit == 0) completionTop1++
                        if (hit >= 0) completionTop3++
                    }
                    if (hit >= 0 && k < token.length && foundAt < 0) foundAt = k
                    if (foundAt >= 0 && k > 0) break
                }
                // Finished word: would space auto-correct a correctly typed word?
                val full = strip.context(text + token)
                val fullShown = lastShown?.takeIf { foundAt < 0 } ?: strip.show(full)
                autoCorrectionChecks++
                // A correct word changed by space. Hamza/ى/ة spelling fixes (انا → أنا) are counted apart.
                val corrected = strip.autoCorrection(token, full.sentenceStart, full.contextWords)?.takeIf { !same(it, token) }
                val normalizedOnly = corrected != null && fold(corrected) == fold(token)
                val wrongCorrection = corrected != null && !normalizedOnly
                if (wrongCorrection) falseCorrections++
                if (normalizedOnly) spellingNormalized++

                val bucket = byLength.getOrPut(minOf(token.length, 10)) { IntArray(2) }
                bucket[0]++
                keysWithout += token.length + 1
                if (foundAt >= 0) {
                    found++
                    lettersToFind += foundAt
                    keysWith += foundAt + 1
                    if (foundAt <= 2) bucket[1]++
                } else {
                    neverFound++
                    keysWith += token.length + 1
                }
                if (foundAt < 0 || wrongCorrection) {
                    misses
                        .append(strip.context(text).contextWords.joinToString(" "))
                        .append('\t')
                        .append(token)
                        .append('\t')
                        .append(fullShown.values.joinToString("|"))
                        .append('\t')
                        .append(if (wrongCorrection) strip.autoCorrection(token, full.sentenceStart, full.contextWords) else "")
                        .append('\n')
                }
                text += token
                // Separator time: the keyboard learns the finished word with the word before it.
                strip.learn(
                    token,
                    strip
                        .context(text)
                        .contextWords
                        .lastOrNull(),
                )
            }
        }
        missFile.writeText(misses.toString())

        fun rate(
            a: Int,
            b: Int,
        ) = if (b == 0) 0.0 else Math.round(a * 10000.0 / b) / 100.0
        return JSONObject()
            .put("sentences", lines.size)
            .put("words", words)
            .put("next_word_top1_pct", rate(predictTop1, predictWords))
            .put("next_word_top3_pct", rate(predictTop3, predictWords))
            .put("first_word_top3_pct", rate(firstTop3, firstWords))
            .put("one_letter_top1_pct", rate(completionTop1, completionWords))
            .put("one_letter_top3_pct", rate(completionTop3, completionWords))
            .put(
                "keystroke_savings_pct",
                if (keysWithout ==
                    0L
                ) {
                    0.0
                } else {
                    Math.round((keysWithout - keysWith) * 10000.0 / keysWithout) / 100.0
                },
            ).put("found_before_end_pct", rate(found, words))
            .put("avg_letters_to_find", if (found == 0) 0.0 else Math.round(lettersToFind * 100.0 / found) / 100.0)
            .put("false_autocorrect_pct", rate(falseCorrections, autoCorrectionChecks))
            .put("false_autocorrect_count", falseCorrections)
            .put("spelling_normalized_pct", rate(spellingNormalized, autoCorrectionChecks))
            .put(
                "found_within_2_letters_by_length",
                JSONObject().also { j ->
                    byLength.toSortedMap().forEach { (len, v) ->
                        j.put(
                            if (len ==
                                10
                            ) {
                                "10+"
                            } else {
                                "$len"
                            },
                            rate(v[1], v[0]),
                        )
                    }
                },
            ).put("latency_desktop_jvm", timer.json())
    }
}

class TypoReplay(
    private val strip: Strip,
    private val classification: WordDictionary,
) {
    fun run(
        rows: List<String>,
        detailFile: File,
    ): JSONObject {
        val kinds = java.util.TreeMap<String, IntArray>() // [rows, fixed by space, wrong fix, intended in strip]
        val detail = StringBuilder("context\ttyped\tintended\tkind\toutcome\tshown\n")
        var total = 0
        for (row in rows) {
            val cols = row.split('\t')
            if (cols.size < 4) continue
            val (context, typed, intended, kind) = cols
            if (typed.isEmpty() || intended.isEmpty()) continue
            total++
            val text = if (context.isBlank()) typed else "${context.trim()} $typed"
            val c = strip.context(text)
            val shown = strip.show(c)
            // A typo that is itself a listed word needs sentence understanding; scored apart.
            val realWord = classification.frequency(typed) > 0
            val stats = kinds.getOrPut(if (realWord) "~real_word_typo" else kind) { IntArray(4) }
            // Real-human sets hold far misspellings (frate → fight) no keyboard fixes; bucket by distance too.
            val distStats =
                kinds.getOrPut(
                    (if (realWord) "~real_word_edits_" else "~edits_") +
                        minOf(editDistance(typed.lowercase(Locale.ROOT), intended.lowercase(Locale.ROOT)), 3).let {
                            if (it ==
                                3
                            ) {
                                "3+"
                            } else {
                                "$it"
                            }
                        },
                ) { IntArray(4) }
            stats[0]++
            distStats[0]++
            val fixed = shown.highlight && same(shown.values[0], intended)
            val wrong = shown.highlight && !same(shown.values[0], intended)
            val offered = shown.values.any { same(it, intended) }
            for (st in listOf(stats, distStats)) {
                if (fixed) st[1]++
                if (wrong) st[2]++
                if (offered) st[3]++
            }
            val outcome =
                when {
                    fixed -> "fixed"
                    wrong -> "wrong_fix"
                    offered -> "offered"
                    else -> "missed"
                }
            detail.append("$context\t$typed\t$intended\t$kind\t$outcome\t${shown.values.joinToString("|")}\n")
        }
        detailFile.writeText(detail.toString())

        fun rate(
            a: Int,
            b: Int,
        ) = if (b == 0) 0.0 else Math.round(a * 10000.0 / b) / 100.0
        val all = IntArray(4)
        val byKind = JSONObject()
        kinds.forEach { (kind, s) ->
            if (!kind.startsWith("~")) for (i in 0..3) all[i] += s[i]
            byKind.put(
                kind,
                JSONObject()
                    .put("rows", s[0])
                    .put("autofixed_pct", rate(s[1], s[0]))
                    .put("wrong_fix_pct", rate(s[2], s[0]))
                    .put("in_strip_pct", rate(s[3], s[0])),
            )
        }
        return JSONObject()
            .put("rows", total)
            .put("scored_rows", all[0])
            .put("autofixed_pct", rate(all[1], all[0]))
            .put("wrong_fix_pct", rate(all[2], all[0]))
            .put("in_strip_pct", rate(all[3], all[0]))
            .put("by_kind", byKind)
    }
}

private fun editDistance(
    a: String,
    b: String,
): Int {
    val d = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        var prev = d[0]
        d[0] = i
        for (j in 1..b.length) {
            val cur = d[j]
            d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
    }
    return d[b.length]
}
