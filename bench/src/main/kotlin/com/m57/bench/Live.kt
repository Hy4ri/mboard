package com.m57.bench

import android.content.Context
import com.m57.mboard.Keys
import com.m57.mboard.Language
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.io.Reader
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * A text field with the caret at its end, plus the key handling of MboardService that changes what the
 * strip shows: onKey's separator branch (autocorrect, learning, double space), autoCorrectBefore,
 * undoCorrection and acceptSuggestion. Keep it in step with MboardService.kt when that code changes.
 */
internal class Editor(
    private val strip: Strip,
    private val autoCapitalize: Boolean,
    initial: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var text = initial
        private set
    lateinit var caret: Strip.Caret
        private set
    lateinit var shown: Strip.Shown
        private set
    var corrections = 0
        private set
    var undos = 0
        private set
    var picks = 0
        private set

    private class Correction(
        val original: String,
        val corrected: String,
        val separator: String,
    )

    private var lastCorrection: Correction? = null
    private var lastSpace = 0L
    private var pickedSpaceTail: String? = null

    init {
        refresh()
    }

    /** Shift follows the text, as MboardService.updateCapitalization does after every edit. */
    private fun refresh() {
        strip.shifted = autoCapitalize && sentenceCaps(text)
        caret = strip.context(text)
        shown = strip.show(caret)
    }

    /** A letter, digit, symbol, or separator key. Returns a note when something notable happened. */
    fun type(key: String): String? {
        strip.predictionTrail.reset()
        val pickedTail = pickedSpaceTail
        pickedSpaceTail = null
        if (key != Keys.SPACE) lastSpace = 0L
        lastCorrection = null
        var note: String? = null
        if (key in SEPARATORS) {
            val before = strip.context(text)
            val typed = before.prefix
            val corrected = strip.autoCorrection(typed, before.sentenceStart, before.contextWords)
            if (corrected != null) {
                text = text.dropLast(typed.length) + corrected
                lastCorrection = Correction(typed, corrected, key)
                corrections++
                note = "auto-corrected $typed → $corrected"
            }
            strip.learn(lastCorrection?.corrected ?: typed, before.contextWords.lastOrNull())
        }
        if (key == Keys.SPACE) {
            val now = clock()
            val canPeriod =
                lastSpace != 0L && now - lastSpace < 600 && text.endsWith(" ") &&
                    text.dropLast(1).lastOrNull()?.isLetterOrDigit() == true
            if (canPeriod) {
                text = text.dropLast(1) + ". "
                lastSpace = 0L
                note = (note?.plus(" · ") ?: "") + "double space → period"
            } else {
                text += " "
                lastSpace = now
            }
        } else {
            if (pickedTail != null && key in SEPARATORS && text.endsWith(pickedTail)) text = text.dropLast(1)
            text += if (strip.shifted) key.uppercase(Locale.ROOT) else key
        }
        refresh()
        return note
    }

    /** Replace the whole field, as if the app had set its text; nothing is learned from it. */
    fun set(value: String) {
        pickedSpaceTail = null
        strip.predictionTrail.reset()
        text = value
        lastSpace = 0L
        lastCorrection = null
        refresh()
    }

    fun enter() {
        pickedSpaceTail = null
        strip.predictionTrail.reset()
        lastSpace = 0L
        lastCorrection = null
        text += "\n"
        refresh()
    }

    /** Backspace straight after an auto-correction puts the typed word back and stops correcting it. */
    fun backspace(): String? {
        pickedSpaceTail = null
        strip.predictionTrail.reset()
        lastSpace = 0L
        val last = lastCorrection
        lastCorrection = null
        val tail = last?.let { it.corrected + it.separator }
        if (last != null && tail != null && text.endsWith(tail)) {
            text = text.dropLast(tail.length) + last.original
            strip.rejectedCorrections += last.original
            strip.learn(last.original, null)
            strip.rejectCorrection(last.original, last.corrected)
            undos++
            refresh()
            return "undid ${last.corrected} → ${last.original}"
        }
        if (text.isNotEmpty()) text = text.substring(0, text.offsetByCodePoints(text.length, -1))
        refresh()
        return null
    }

    /** Tap on suggestion [slot]: the word replaces the one at the caret and a space follows. */
    fun pick(slot: Int): String? {
        val word = shown.values.getOrNull(slot) ?: return "nothing in slot ${slot + 1}"
        val context = caret
        text = text.dropLast(context.prefix.length) + word.trimEnd() + " "
        pickedSpaceTail = (word.trimEnd() + " ").takeLast(33)
        if (strip.language.script == com.m57.mboard.Script.LATIN &&
            com.m57.mboard.Arabizi
                .isReading(word)
        ) {
            strip.predictionTrail.reset()
        } else if (' ' !in word.trim()) {
            strip.acceptCorrection(context.prefix, word.trim())
            strip.predictionTrail.record(context.contextWords, word.trim())
            strip.learn(word.trim(), context.contextWords.lastOrNull())
        } else {
            strip.predictionTrail.reset()
        }
        lastSpace = 0L
        lastCorrection = null
        picks++
        refresh()
        return "picked $word"
    }

    private companion object {
        // MboardService.separators
        val SEPARATORS = setOf(Keys.SPACE, ".", ",", "،", "?", "؟", "!", ";", "؛", ":")
    }
}

private sealed interface Press {
    data class Text(
        val value: String,
    ) : Press

    data class Pick(
        val slot: Int,
    ) : Press

    data object Backspace : Press

    data object Enter : Press

    data object Ignore : Press

    data object Quit : Press
}

private fun waitReady(reader: Reader): Boolean {
    repeat(30) {
        if (reader.ready()) return true
        Thread.sleep(1)
    }
    return reader.ready()
}

/** ESC alone is ignored; ESC O P/Q/R (F1-F3) and ESC 1/2/3 (Alt-1/2/3) pick; other ESC [ … and ESC O … are swallowed. */
private fun escape(reader: Reader): Press {
    if (!waitReady(reader)) return Press.Ignore
    val next = reader.read()
    return when (next) {
        '['.code -> {
            // CSI: parameter and intermediate bytes, then one final byte.
            var b: Int
            do {
                b = reader.read()
            } while (b in 0x20..0x3F)
            Press.Ignore
        }

        'O'.code -> {
            when (reader.read()) {
                'P'.code -> Press.Pick(0)
                'Q'.code -> Press.Pick(1)
                'R'.code -> Press.Pick(2)
                else -> Press.Ignore
            }
        }

        in '1'.code..'3'.code -> {
            Press.Pick(next - '1'.code)
        }

        else -> {
            Press.Ignore
        }
    }
}

private fun readPress(reader: Reader): Press {
    val c = reader.read()
    return when {
        c < 0 || c == 3 || c == 4 -> {
            Press.Quit
        }

        c == 127 || c == 8 -> {
            Press.Backspace
        }

        c == 13 || c == 10 -> {
            Press.Enter
        }

        c == 9 -> {
            Press.Pick(0)
        }

        c == 27 -> {
            escape(reader)
        }

        c < 32 -> {
            Press.Ignore
        }

        Character.isHighSurrogate(c.toChar()) -> {
            val low = reader.read()
            if (low < 0) Press.Quit else Press.Text(String(charArrayOf(c.toChar(), low.toChar())))
        }

        else -> {
            Press.Text(c.toChar().toString())
        }
    }
}

private fun isTty(): Boolean =
    try {
        ProcessBuilder("sh", "-c", "test -t 0 && test -t 1").inheritIO().start().waitFor() == 0
    } catch (_: java.io.IOException) {
        false
    }

private fun stty(args: String): String {
    val process = ProcessBuilder("sh", "-c", "stty $args < /dev/tty").redirectErrorStream(true).start()
    val output =
        process.inputStream
            .bufferedReader()
            .readText()
            .trim()
    check(process.waitFor() == 0) { "stty $args failed: $output" }
    return output
}

private const val DIM = "\u001b[2m"
private const val RESET = "\u001b[0m"

internal fun slotsText(
    editor: Editor,
    separator: String,
): String =
    editor.shown.values
        .mapIndexed { i, word -> if (i == 0 && editor.shown.highlight) "$word (auto)" else word }
        .joinToString(separator)
        .ifEmpty { "—" }

/** The four lines drawn under the header and redrawn after every key, each clipped to the terminal width. */
private fun frame(
    editor: Editor,
    cols: Int,
): List<String> {
    val room = maxOf(20, cols - 10)
    val tail = editor.text.takeLast(room - 2)
    val text = (if (tail.length < editor.text.length) "…" else "") + visible(tail) + "▌"
    val strip = slotsText(editor, "  │  ").let { if (it.length > room) it.take(room - 1) + "…" else it }
    val caret = editor.caret
    val mode =
        when {
            caret.idle -> "next word"
            caret.prefix.isEmpty() -> "no word under the caret"
            else -> "completing “${caret.prefix}”"
        }
    val after = caret.contextWords.takeLast(3).joinToString(" ")
    val now = (mode + if (after.isEmpty()) "" else " · after: $after").let { if (it.length > room) it.take(room - 1) + "…" else it }
    return listOf(
        "${DIM}text   $RESET$text",
        "${DIM}strip  $RESET$strip",
        "${DIM}now    $RESET$now",
        "${DIM}keys   Tab/F1-F3/Alt-1-3 pick · ⌫ delete or undo · Ctrl-D quit$RESET",
    )
}

internal fun cell(value: String) = value.replace("|", "\\|").replace("\n", "⏎")

/**
 * Live mode: every key is applied to a simulated field and the strip is redrawn at once. The terminal runs
 * raw when stdin is a real one; with piped input each character is a key and every step prints as a line.
 * The markdown log is appended per key, so even a killed session leaves its steps behind.
 */
internal fun runLive(
    opts: Options,
    language: Language,
    dialect: Boolean,
) {
    val out = PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8")
    val tty = isTty()
    if (tty) {
        out.print("Loading ${language.name} dictionary…")
        out.flush()
    }
    val started = System.nanoTime()
    val strip = Strip(Context(opts.assets), language, dialect, opts.learning, opts.autoCorrect, opts.arabizi)
    val loadMs = (System.nanoTime() - started) / 1_000_000
    val editor = Editor(strip, opts.autoCapitalize && language.cased, opts.input.orEmpty())
    if (tty) out.print("\r\u001b[2K")

    val now = ZonedDateTime.now()
    val log = uniqueLog(opts.logDir, now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")), "-live")
    val settings =
        listOf(
            "${language.name} (${language.code})",
            "dialect ${if (language.dialect == null) "n/a" else onOff(dialect)}",
            "autocorrect ${onOff(opts.autoCorrect)}",
            "auto-capitalize ${onOff(opts.autoCapitalize && language.cased)}",
            "learning ${onOff(opts.learning)}",
        )
    log.writeText(
        buildString {
            appendLine("# mboard suggestions · live session")
            appendLine()
            appendLine("Run ${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx"))} · engine ${engineRev()}")
            appendLine()
            appendLine(settings.joinToString(" · "))
            appendLine()
            appendLine("Dictionary load $loadMs ms. Rendering is desktop JVM, not phone latency.")
            appendLine()
            appendLine("## Keystrokes")
            appendLine()
            appendLine("| # | Key | Text | Suggestions | Note |")
            appendLine("|--:|---|---|---|---|")
        },
    )

    var saved: String? = null
    if (tty) {
        saved = stty("-g")
        stty("-icanon -echo -isig min 1 time 0")
    }
    val finished = AtomicBoolean(false)
    var keyCount = 0

    fun finish() {
        if (!finished.compareAndSet(false, true)) return
        saved?.let { runCatching { stty(it) } }
        log.appendText(
            buildString {
                appendLine()
                appendLine("## Result")
                appendLine()
                appendLine("```")
                appendLine(editor.text)
                appendLine("```")
                appendLine()
                appendLine(
                    "$keyCount keys · ${editor.corrections} auto-corrections · ${editor.undos} undone · " +
                        "${editor.picks} suggestions picked · ${(System.nanoTime() - started) / 1_000_000_000} s",
                )
            },
        )
    }
    Runtime.getRuntime().addShutdownHook(Thread { finish() })

    var drawn = false

    fun show(
        label: String,
        note: String?,
    ) {
        val markdownKey = if (label == "`") "`` ` ``" else "`${cell(label)}`"
        val strips = slotsText(editor, " · ")
        log.appendText(
            "| $keyCount | $markdownKey | `${cell(visible(editor.text.takeLast(48)))}` | ${cell(strips)} | ${cell(note.orEmpty())} |\n",
        )
        if (tty) {
            // Re-read the width each key so a resized window cannot leave wrapped lines behind.
            val lines = frame(editor, stty("size").split(' ').getOrNull(1)?.toIntOrNull() ?: 80)
            val builder = StringBuilder()
            if (drawn) builder.append("\u001b[${lines.size}A")
            lines.forEach { builder.append("\r\u001b[2K").append(it).append('\n') }
            out.print(builder)
            drawn = true
        } else {
            val text = visible(editor.text.takeLast(40)).padEnd(40)
            out.println(
                "${keyCount.toString().padStart(
                    3,
                )}  ${label.padEnd(7)} $text │ ${slotsText(editor, " │ ")}${note?.let { "   # $it" }.orEmpty()}",
            )
        }
    }

    out.println("mboard live · ${settings.joinToString(" · ")}${if (tty) "" else " · one line per key"}")
    show("start", null)
    val reader = System.`in`.reader(Charsets.UTF_8)
    try {
        loop@ while (true) {
            val press = readPress(reader)
            val label: String
            val note: String?
            when (press) {
                Press.Quit -> {
                    break@loop
                }

                Press.Ignore -> {
                    continue@loop
                }

                Press.Backspace -> {
                    label = "⌫"
                    note = editor.backspace()
                }

                Press.Enter -> {
                    label = "⏎"
                    editor.enter()
                    note = null
                }

                is Press.Pick -> {
                    label = "pick ${press.slot + 1}"
                    note = editor.pick(press.slot)
                }

                is Press.Text -> {
                    label = if (press.value == " ") "␣" else press.value
                    note = editor.type(press.value)
                }
            }
            keyCount++
            show(label, note)
        }
    } finally {
        finish()
    }
    out.println("Log: ${log.absolutePath}")
    exitProcess(0)
}
