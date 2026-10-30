package com.m57.bench

import android.content.Context
import com.m57.mboard.Language
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

internal const val AGENT_PROTOCOL = """Agent protocol (--agent): one JSON object per stdin line, one JSON object per stdout line.

  {"type": "hi there"}          type the characters one by one, as key presses (space, . , ? ! etc. are
                                separators: they auto-correct and learn exactly like the keyboard)
  {"key": "backspace"}          one key: backspace | enter | space; add "times": N to repeat
  {"pick": 1}                   tap suggestion slot 1, 2 or 3 (replaces the word at the caret, adds a space)
  {"set": "some text"}          replace the whole field (nothing is learned, caret at the end)
  {"wait": 800}                 let virtual time pass, in ms (each key press takes 100 ms; two spaces
                                within 600 ms make ". ", as on the phone)
  {"state": true}               report the current state without changing it
  {"quit": true}                end the session (closing stdin does the same)

  Any command may carry "id": anything, echoed back in the reply.

Every reply: {"ok": true, "seq", "id"?, "text", "mode": "next"|"complete"|"none", "word", "context",
"suggestions": [up to 3], "auto": the word space would auto-correct to, or null, "keys", "events": what
the command did (auto-corrections, undo, double-space period, picks)}. Errors: {"ok": false, "error"}; the
session keeps going. The first line is {"event": "ready", ...} and the last is {"event": "end", ...}.
Everything (commands, every key, every reply) is logged to <log-dir>/<stamp>-agent.md and .jsonl."""

private const val KEY_MS = 100L

private val ACTIONS = listOf("type", "key", "pick", "set", "wait", "state", "quit")

/** One key applied by a command, kept for the logs. */
private class Step(
    val key: String,
    val text: String,
    val strip: String,
    val note: String?,
)

internal fun stateJson(editor: Editor): JSONObject {
    val caret = editor.caret
    val shown = editor.shown
    return JSONObject()
        .put("text", editor.text)
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
}

/**
 * Live mode for programs: JSON commands in, one JSON reply per command out, every key logged. Time is
 * virtual, so a session replays identically; see [AGENT_PROTOCOL].
 */
internal fun runAgent(
    opts: Options,
    language: Language,
    dialect: Boolean,
) {
    val out = PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8")
    val started = System.nanoTime()
    val strip = Strip(Context(opts.assets), language, dialect, opts.learning, opts.autoCorrect, opts.arabizi)
    val loadMs = (System.nanoTime() - started) / 1_000_000
    var clock = 0L
    val editor = Editor(strip, opts.autoCapitalize && language.cased, opts.input.orEmpty()) { clock }

    val now = ZonedDateTime.now()
    val md = uniqueLog(opts.logDir, now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")), "-agent")
    val jsonl = File(md.path.removeSuffix(".md") + ".jsonl")
    val settings =
        JSONObject()
            .put("language", language.code)
            .put("dialect", if (language.dialect == null) JSONObject.NULL else dialect)
            .put("autocorrect", opts.autoCorrect)
            .put("autocap", opts.autoCapitalize && language.cased)
            .put("learning", opts.learning)

    fun record(obj: JSONObject) = jsonl.appendText(obj.toString() + "\n")

    md.writeText(
        buildString {
            appendLine("# mboard suggestions · agent session")
            appendLine()
            appendLine("Run ${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx"))} · engine ${engineRev()}")
            appendLine()
            appendLine(
                "${language.name} (${language.code}) · dialect ${if (language.dialect == null) "n/a" else onOff(dialect)} · " +
                    "autocorrect ${onOff(opts.autoCorrect)} · auto-capitalize ${onOff(opts.autoCapitalize && language.cased)} · " +
                    "learning ${onOff(opts.learning)}",
            )
            appendLine()
            appendLine("Dictionary load $loadMs ms. Virtual time: $KEY_MS ms per key. Machine log: `${jsonl.name}`.")
            appendLine()
            appendLine(
                "Start: ${if (editor.text.isEmpty()) {
                    "(empty field)"
                } else {
                    "`${cell(
                        visible(editor.text),
                    )}`"
                }} → ${cell(slotsText(editor, " · "))}",
            )
            appendLine()
            appendLine("## Commands")
            appendLine()
            appendLine("| Cmd | Key | # | Text | Suggestions | Note |")
            appendLine("|--:|---|--:|---|---|---|")
        },
    )

    var seq = 0
    var keyCount = 0
    val finished = AtomicBoolean(false)

    fun finish() {
        if (!finished.compareAndSet(false, true)) return
        val end =
            JSONObject()
                .put("event", "end")
                .put("commands", seq)
                .put("keys", keyCount)
                .put("corrections", editor.corrections)
                .put("undone", editor.undos)
                .put("picked", editor.picks)
                .put("text", editor.text)
                .put("log", md.absolutePath)
                .put("jsonl", jsonl.absolutePath)
        record(end)
        md.appendText(
            buildString {
                appendLine()
                appendLine("## Result")
                appendLine()
                appendLine("```")
                appendLine(editor.text)
                appendLine("```")
                appendLine()
                appendLine(
                    "$seq commands · $keyCount keys · ${editor.corrections} auto-corrections · ${editor.undos} undone · " +
                        "${editor.picks} suggestions picked · ${(System.nanoTime() - started) / 1_000_000} ms wall",
                )
            },
        )
        out.println(end)
    }
    Runtime.getRuntime().addShutdownHook(Thread { finish() })

    val ready =
        JSONObject()
            .put("event", "ready")
            .put("settings", settings)
            .put("log", md.absolutePath)
            .put("jsonl", jsonl.absolutePath)
    stateJson(editor).let { s -> s.keys().forEach { ready.put(it, s.get(it)) } }
    record(ready)
    out.println(ready)

    val reader = System.`in`.bufferedReader(Charsets.UTF_8)
    while (true) {
        val line = reader.readLine() ?: break
        if (line.isBlank()) continue
        seq++
        record(JSONObject().put("event", "command").put("seq", seq).put("raw", line))
        val steps = ArrayList<Step>()

        fun press(
            label: String,
            action: () -> String?,
        ) {
            clock += KEY_MS
            val note = action()
            keyCount++
            steps += Step(label, editor.text, slotsText(editor, " · "), note)
        }

        val reply = JSONObject().put("seq", seq)
        var quit = false
        try {
            val cmd = JSONObject(line)
            if (cmd.has("id")) reply.put("id", cmd.get("id"))
            val actions = ACTIONS.filter { cmd.has(it) }
            require(actions.size == 1) {
                if (actions.isEmpty()) {
                    "unknown command; use type, key, pick, set, wait, state or quit"
                } else {
                    "one action per command, got ${actions.joinToString(", ")}"
                }
            }
            when {
                cmd.has("type") -> {
                    val value = cmd.getString("type")
                    var i = 0
                    while (i < value.length) {
                        val end = value.offsetByCodePoints(i, 1)
                        val ch = value.substring(i, end)
                        i = end
                        when (ch) {
                            "\n" -> press("⏎") { editor.enter().let { null } }
                            "\b" -> press("⌫") { editor.backspace() }
                            else -> press(if (ch == " ") "␣" else ch) { editor.type(ch) }
                        }
                    }
                }

                cmd.has("key") -> {
                    val times = cmd.optInt("times", 1)
                    require(times in 1..1000) { "times must be 1..1000" }
                    val key = cmd.getString("key")
                    repeat(times) {
                        when (key) {
                            "backspace" -> press("⌫") { editor.backspace() }
                            "enter" -> press("⏎") { editor.enter().let { null } }
                            "space" -> press("␣") { editor.type(" ") }
                            else -> throw IllegalArgumentException("unknown key \"$key\"; use backspace, enter or space")
                        }
                    }
                }

                cmd.has("pick") -> {
                    val slot = cmd.getInt("pick")
                    require(slot in 1..3) { "pick must be 1, 2 or 3" }
                    require(slot <= editor.shown.values.size) { "slot $slot is empty; strip is ${editor.shown.values}" }
                    press("pick $slot") { editor.pick(slot - 1) }
                }

                cmd.has("set") -> {
                    editor.set(cmd.getString("set"))
                    steps += Step("set", editor.text, slotsText(editor, " · "), "field set")
                }

                cmd.has("wait") -> {
                    val ms = cmd.getLong("wait")
                    require(ms >= 0) { "wait must be >= 0" }
                    clock += ms
                    steps += Step("wait", editor.text, slotsText(editor, " · "), "waited $ms ms")
                }

                cmd.has("state") -> {}

                cmd.has("quit") -> {
                    quit = true
                }

                else -> {
                    throw IllegalArgumentException("unknown command; use type, key, pick, set, wait, state or quit")
                }
            }
            reply.put("ok", true)
            stateJson(editor).let { s -> s.keys().forEach { reply.put(it, s.get(it)) } }
            reply.put("keys", steps.count { it.key != "set" && it.key != "wait" })
            reply.put("events", JSONArray(steps.mapNotNull { it.note }))
        } catch (e: JSONException) {
            reply.put("ok", false).put("error", "bad JSON: ${e.message}")
        } catch (e: IllegalArgumentException) {
            reply.put("ok", false).put("error", e.message)
        }

        // Keys that ran before an error still happened; log them either way.
        md.appendText(
            buildString {
                val label = cell(line.take(60) + if (line.length > 60) "…" else "")
                if (steps.isEmpty()) {
                    val note = if (reply.optBoolean("ok")) "" else "error: ${reply.optString("error")}"
                    appendLine(
                        "| $seq | `$label` | | `${cell(
                            visible(editor.text.takeLast(48)),
                        )}` | ${cell(slotsText(editor, " · "))} | ${cell(note)} |",
                    )
                } else {
                    appendLine("| $seq | `$label` | | | | |")
                    val base = keyCount - steps.count { it.key != "set" && it.key != "wait" }
                    var n = base
                    for (s in steps) {
                        val number = if (s.key == "set" || s.key == "wait") "" else (++n).toString()
                        val key = if (s.key == "`") "`` ` ``" else "`${cell(s.key)}`"
                        appendLine(
                            "| | $key | $number | `${cell(visible(s.text.takeLast(48)))}` | ${cell(s.strip)} | ${cell(s.note.orEmpty())} |",
                        )
                    }
                    if (!reply.optBoolean("ok")) appendLine("| | | | | | error: ${cell(reply.optString("error"))} |")
                }
            },
        )
        steps.forEachIndexed { index, s ->
            record(
                JSONObject()
                    .put("event", "key")
                    .put("seq", seq)
                    .put("n", index + 1)
                    .put("key", s.key)
                    .put("text", s.text)
                    .put("strip", s.strip)
                    .put("note", s.note ?: JSONObject.NULL),
            )
        }
        record(JSONObject(reply.toString()).put("event", "reply"))
        out.println(reply)
        if (quit) break
    }
    finish()
    exitProcess(0)
}
