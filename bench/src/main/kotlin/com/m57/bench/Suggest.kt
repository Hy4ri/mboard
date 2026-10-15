package com.m57.bench

import android.content.Context
import com.m57.mboard.Languages
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * One keyboard-strip lookup. The text is what sits in the editor with the caret at its end; the output is
 * the three suggestions the keyboard would show. Text ending in a space (or empty) shows next words.
 *
 * Every run is its own process with fresh in-memory preferences, so nothing is learned or carried over.
 * The engine is the keyboard's own source (see bench/build.gradle.kts), driven through [Strip].
 */
private const val USAGE = """usage: bench/suggest.sh [options] TEXT
       bench/suggest.sh --live [options] [TEXT]
       bench/suggest.sh --agent [options] [TEXT]

  TEXT                 the editor content, caret at the end. Quote it: a trailing space means
                       "show the next word"; no trailing space means "complete this word".
                       With --live it is only the text the field starts with (default empty).

  --live               interactive: type into a simulated field and watch the strip update on every
                       key. Tab / F1 / F2 / F3 or Alt-1/2/3 tap suggestion 1/2/3, Backspace undoes an
                       autocorrect right after it, Ctrl-D quits. Needs a terminal for per-key redraw;
                       piped input is read as keys too and prints one line per key.
  --agent              live mode for programs: JSON commands on stdin, one JSON reply per line on
                       stdout, every key logged to .md and .jsonl. See --help-agent.
  --json               (one lookup) print one JSON object instead of the markdown report

  --lang auto|en|ar    typing language (default auto: Arabic if TEXT has Arabic letters, else English)
  --dialect            Jordanian Arabic vocabulary on (off by default, as in the keyboard)
  --no-autocorrect     autocorrect off (on by default)
  --no-autocap         field without sentence capitalization (default: capitalize at a sentence start)
  --no-learning        (live) do not learn the words you type or pick (on by default, as in the keyboard)
  --log-dir DIR        where the run log goes (default bench/logs/suggest)
  --assets DIR         dictionary assets (default app/src/main/assets)
  --                   end of options, for TEXT that starts with --
  --help               this text
  --help-agent         the --agent protocol"""

internal class Options(
    val input: String?,
    val live: Boolean,
    val agent: Boolean,
    val json: Boolean,
    val lang: String,
    val dialect: Boolean,
    val autoCorrect: Boolean,
    val autoCapitalize: Boolean,
    val learning: Boolean,
    val assets: File,
    val logDir: File,
)

private fun fail(message: String): Nothing {
    System.err.println("suggest: $message\n")
    System.err.println(USAGE)
    exitProcess(2)
}

private fun parseOptions(args: Array<String>): Options {
    var lang = "auto"
    var dialect = false
    var autoCorrect = true
    var autoCapitalize = true
    var live = false
    var agent = false
    var json = false
    var learning = true
    var assets = File("../app/src/main/assets")
    var logDir = File("logs/suggest")
    val positional = ArrayList<String>()
    var literal = false
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        if (literal || !arg.startsWith("--")) {
            positional += arg
        } else {
            when (arg) {
                "--" -> {
                    literal = true
                }

                "--help" -> {
                    println(USAGE)
                    exitProcess(0)
                }

                "--lang" -> {
                    lang = args.getOrNull(++i) ?: fail("--lang needs a value")
                }

                "--live" -> {
                    live = true
                }

                "--agent" -> {
                    agent = true
                }

                "--json" -> {
                    json = true
                }

                "--help-agent" -> {
                    println(AGENT_PROTOCOL)
                    exitProcess(0)
                }

                "--no-learning" -> {
                    learning = false
                }

                "--dialect" -> {
                    dialect = true
                }

                "--no-autocorrect" -> {
                    autoCorrect = false
                }

                "--no-autocap" -> {
                    autoCapitalize = false
                }

                "--assets" -> {
                    assets = File(args.getOrNull(++i) ?: fail("--assets needs a directory"))
                }

                "--log-dir" -> {
                    logDir = File(args.getOrNull(++i) ?: fail("--log-dir needs a directory"))
                }

                else -> {
                    fail("unknown option $arg")
                }
            }
        }
        i++
    }
    if (live && agent) fail("--live and --agent are separate modes; pick one")
    val session = live || agent
    if (json && session) fail("--json is for a single lookup; --agent already speaks JSON")
    if (positional.size > 1 || (positional.isEmpty() && !session)) {
        fail("expected ${if (session) "at most" else "exactly"} one TEXT argument, got ${positional.size}; quote it")
    }
    return Options(positional.firstOrNull(), live, agent, json, lang, dialect, autoCorrect, autoCapitalize, learning, assets, logDir)
}

/**
 * Android's sentence capitalization, simplified: start of text, a new line, or a . ! ? followed by a space.
 * Only the first letter of a prediction depends on it, never which words are offered.
 */
internal fun sentenceCaps(text: String): Boolean {
    val trimmed = text.trimEnd(' ', '\t')
    if (trimmed.isEmpty() || trimmed.last() == '\n') return true
    if (trimmed.length == text.length) return false
    return trimmed.last() in ".!?"
}

/** The text with its invisible parts drawn: trailing spaces, tabs and line breaks. */
internal fun visible(text: String): String {
    val body = text.trimEnd(' ')
    return (body + "␣".repeat(text.length - body.length)).replace("\n", "⏎").replace("\t", "⇥")
}

internal fun onOff(value: Boolean) = if (value) "on" else "off"

internal fun uniqueLog(
    dir: File,
    stamp: String,
    kind: String = "",
): File {
    dir.mkdirs()
    var n = 1
    while (true) {
        val file = File(dir, if (n == 1) "$stamp$kind.md" else "$stamp$kind-$n.md")
        if (file.createNewFile()) return file
        n++
    }
}

internal fun engineRev(): String = System.getProperty("suggest.rev") ?: "unknown"

fun main(args: Array<String>) {
    val opts = parseOptions(args)
    if (!opts.assets.isDirectory) fail("assets directory not found: ${opts.assets}")
    val input = opts.input.orEmpty()
    val language =
        when (opts.lang) {
            "auto" -> Languages.byCode(if (input.any { it in '؀'..'ۿ' }) "ar" else "en")
            else -> Languages.ALL.firstOrNull { it.code == opts.lang } ?: fail("unknown language ${opts.lang}; use en, ar or auto")
        }
    val dialect = opts.dialect && language.dialect != null
    if (opts.live) runLive(opts, language, dialect)
    if (opts.agent) runAgent(opts, language, dialect)

    val loadStart = System.nanoTime()
    val strip = Strip(Context(opts.assets), language, dialect, learning = true, autoCorrect = opts.autoCorrect)
    val loadMs = (System.nanoTime() - loadStart) / 1_000_000
    val shifted = opts.autoCapitalize && language.cased && sentenceCaps(input)
    strip.shifted = shifted

    val caret = strip.context(input)
    val renderStart = System.nanoTime()
    val shown = strip.show(caret)
    val renderMs = (System.nanoTime() - renderStart) / 1_000_000.0
    strip.words.close()

    val now = ZonedDateTime.now()
    val log = uniqueLog(opts.logDir, now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")))
    val rev = engineRev()

    val notes = ArrayList<String>()
    if (shown.highlight) notes += "Space replaces `${caret.prefix}` with `${shown.values.first()}` (slot 1 is an auto-correction)."
    if (input.length > 120) notes += "Long text: the keyboard reads only the last 120 characters, and so did this run."
    if (opts.dialect && language.dialect == null) notes += "${language.name} has no dialect vocabulary, so `--dialect` changed nothing."
    if (shown.values.isEmpty()) notes += "The keyboard would show an empty strip here."

    val report =
        buildString {
            appendLine("# mboard suggestions")
            appendLine()
            appendLine("Run ${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx"))} · engine $rev")
            appendLine()
            appendLine("## Input")
            appendLine()
            appendLine("`${visible(input)}`")
            appendLine()
            appendLine("| | |")
            appendLine("|---|---|")
            appendLine("| Shows | ${if (caret.idle) "Next word" else "Completions for `${caret.prefix}`"} |")
            appendLine("| Context | ${caret.contextWords.joinToString(" ").ifEmpty { "(none)" }} |")
            appendLine("| Sentence start | ${if (caret.sentenceStart) "yes" else "no"} |")
            appendLine("| Language | ${language.name} (${language.code}) |")
            appendLine("| Dialect | ${if (language.dialect == null) "n/a" else "${language.dialect!!.title} ${onOff(dialect)}"} |")
            appendLine("| Autocorrect | ${onOff(opts.autoCorrect)} |")
            appendLine("| Shift | ${if (!language.cased) "n/a" else "${onOff(shifted)} (auto-capitalize ${onOff(opts.autoCapitalize)})"} |")
            appendLine()
            appendLine("## Suggestions")
            appendLine()
            appendLine("| Slot | Suggestion |")
            appendLine("|---|---|")
            for (slot in 0 until 3) {
                val word = shown.values.getOrNull(slot)
                appendLine(
                    "| ${slot + 1} | ${if (word == null) "—" else "**$word**"}${if (slot == 0 && shown.highlight) " ← auto" else ""} |",
                )
            }
            if (notes.isNotEmpty()) {
                appendLine()
                appendLine("## Notes")
                appendLine()
                notes.forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine("---")
            appendLine("Dictionary load $loadMs ms · render ${"%.1f".format(renderMs)} ms (cold, desktop JVM, not phone latency)")
        }
    log.writeText(report)
    if (opts.json) {
        val result =
            JSONObject()
                .put("text", input)
                .put(
                    "mode",
                    when {
                        caret.idle -> "next"
                        caret.prefix.isEmpty() -> "none"
                        else -> "complete"
                    },
                ).put("word", caret.prefix)
                .put("context", JSONArray(caret.contextWords))
                .put("suggestions", JSONArray(shown.values))
                .put("auto", if (shown.highlight) shown.values.firstOrNull() else JSONObject.NULL)
                .put("language", language.code)
                .put("log", log.absolutePath)
        println(result)
    } else {
        print(report)
        println("Log: ${log.absolutePath}")
    }
    exitProcess(0)
}
