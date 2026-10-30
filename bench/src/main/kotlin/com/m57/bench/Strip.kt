package com.m57.bench

import android.content.Context
import com.m57.mboard.Language
import com.m57.mboard.PredictionTrail
import com.m57.mboard.UserWords
import com.m57.mboard.WordDictionary
import com.m57.mboard.WordSuggestions
import java.util.Locale

/**
 * The suggestion strip as MboardService builds it, for an editor whose caret sits at the end of [text].
 *
 * The dictionary, ranking, prediction and learning are the keyboard's real classes. Only the glue in
 * MboardService (prefixContext, renderSuggestions, autoCorrection, learn) is mirrored here, line for line,
 * because the service itself needs Android. Keep it in step with MboardService.kt when that glue changes.
 */
class Strip(
    context: Context,
    val language: Language,
    val dialect: Boolean,
    val learning: Boolean,
    val autoCorrect: Boolean = true,
    val arabizi: Boolean = false,
) {
    val words = WordSuggestions(context)
    val predictionTrail = PredictionTrail()
    val userWords = UserWords(context)
    val rejectedCorrections = HashSet<String>()

    /** The bundled word list, to tell real-word typos (buy → but) from non-words. */
    val dictionary: WordDictionary =
        context.assets
            .open(language.wordsAsset)
            .bufferedReader()
            .use { WordDictionary.read(it, language) }

    init {
        words.load(language) {}
        if (arabizi && language.script == com.m57.mboard.Script.LATIN) {
            words.loadArabizi {}
            val until = System.currentTimeMillis() + 60_000
            while (!words.arabiziReady()) {
                check(System.currentTimeMillis() < until) { "arabizi lexicon load timed out" }
                Thread.sleep(5)
            }
        }
        val deadline = System.currentTimeMillis() + 60_000
        while (words.state(language) == WordSuggestions.State.LOADING || words.state(language) == WordSuggestions.State.UNLOADED) {
            check(System.currentTimeMillis() < deadline) { "dictionary load timed out" }
            Thread.sleep(5)
        }
        check(words.state(language) == WordSuggestions.State.READY) { "dictionary failed: ${words.state(language)}" }
    }

    class Caret(
        val prefix: String,
        val idle: Boolean,
        val contextWords: List<String>,
        val sentenceStart: Boolean,
    )

    class Shown(
        val values: List<String>,
        /** First value is an auto-correction that space will apply. */
        val highlight: Boolean,
    )

    // MboardService.wordChar
    fun wordChar(char: Char): Boolean =
        char.isLetterOrDigit() || Character.getType(char) == Character.NON_SPACING_MARK.toInt() || char == '\''

    // MboardService.prefixContext with nothing after the caret.
    fun context(text: String): Caret {
        val before = text.takeLast(120)
        val result = before.takeLastWhile(::wordChar)
        val idle = result.isEmpty() && before.lastOrNull()?.isWhitespace() != false
        val beforeWord = before.dropLast(result.length).trimEnd(' ', '\t')
        val sentenceStart = beforeWord.isEmpty() || beforeWord.last() in ".!?؟\n"
        val recent =
            before
                .dropLast(result.length)
                .takeLastWhile { wordChar(it) || it == ' ' || it == '\t' }
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .takeLast(16)
        return Caret(result, idle, recent, sentenceStart)
    }

    /** Keyboard shift state (auto-capitalize at a sentence start); the replay bench keeps it off. */
    var shifted = false

    private fun matchCase(
        word: String,
        source: String,
    ) = WordDictionary.displayWord(word, source, language, shifted, false)

    private fun learnedNext(contextWords: List<String>): Map<String, Int> =
        if (!learning) emptyMap() else contextWords.lastOrNull()?.let { userWords.next(it, language) }.orEmpty()

    // MboardService.autoCorrection
    fun autoCorrection(
        typed: String,
        wordStart: Boolean,
        contextWords: List<String> = emptyList(),
    ): String? {
        if (!autoCorrect || typed.isEmpty() || typed in rejectedCorrections) return null
        if (userWords.knows(typed, language)) return null
        if (language.cased && typed.first().isUpperCase() && !wordStart && typed != "i") return null
        val correction =
            words.correction(typed, language, contextWords, dialect, learnedNext(contextWords))?.let { matchCase(it, typed) } ?: return null
        return if (correction == typed || userWords.rejects(typed, correction, language)) null else correction
    }

    // MboardService.renderSuggestions, ordinary text field, no saved phrases, emoji suggestions off.
    fun show(c: Caret): Shown {
        if (c.idle) {
            val predicted =
                words
                    .predict(c.contextWords, language, dialect, learnedNext(c.contextWords))
                    .let { predictionTrail.filter(c.contextWords, it) }
            return Shown(predicted.map { matchCase(it, "") }.take(3), false)
        }
        val typed = c.prefix
        val autoCorrection = autoCorrection(typed, c.sentenceStart, c.contextWords)
        val hint =
            if (autoCorrection == null && autoCorrect && !userWords.knows(typed, language)) {
                words.likelyIntended(typed, language)?.let { matchCase(it, typed) }
            } else {
                null
            }
        // Space missed between two words ("fixxit" → "fix it"): a suggestion only, never applied on space.
        val splitHint =
            if (!userWords.knows(typed, language)) {
                words.splitSuggestion(typed, language, c.contextWords, dialect)
            } else {
                null
            }
        val ranked =
            words
                .rank(
                    typed,
                    c.contextWords,
                    language,
                    dialect,
                    learnedNext(c.contextWords),
                    if (!learning) emptyMap() else userWords.completions(typed, language),
                ).map { matchCase(it, typed) }
        // A split leads only when no one-word typo candidate (fuzzy, two-edit, rule) is ranked, or the pair is very common.
        val split = splitHint?.text?.let { matchCase(it, typed) }
        val oneWordTypo = ranked.any { !it.startsWith(typed, ignoreCase = true) }
        val leading = autoCorrection ?: hint ?: split?.takeIf { splitHint?.strong == true || !oneWordTypo }
        val others =
            (listOfNotNull(leading) + ranked)
                .filter { it != typed }
                .distinctBy { it.lowercase(Locale.ROOT) }
                .toMutableList()
        if (split != null && split != leading && others.none { it.equals(split, ignoreCase = true) }) {
            others.add(minOf(1, others.size), split)
        }
        val keepAt = if (leading != null && leading != typed) 1 else 0
        val wordChoices = if (typed.length >= 2) others.take(keepAt) + typed + others.drop(keepAt) else others
        // MboardService: Arabizi readings (suggest-only); the app skips them when the caret is inside a word.
        val readings = if (arabizi) words.arabizi(typed, language, 2) else emptyList()
        return Shown(
            com.m57.mboard.Arabizi
                .place(wordChoices, typed, readings, lastSlotOnly = words.isArabiziListed(typed))
                .take(3),
            autoCorrection != null,
        )
    }

    // The app calls these only under its normal recording guard (private/password/no-learning off).
    fun rejectCorrection(
        typed: String,
        corrected: String,
    ) {
        if (!learning) return
        userWords.reject(typed, corrected, language)
        userWords.save()
    }

    fun acceptCorrection(
        typed: String,
        corrected: String,
    ) {
        if (!learning || !userWords.rejects(typed, corrected, language)) return
        userWords.acceptCorrection(typed, corrected, language)
        userWords.save()
    }

    // MboardService.learn in an ordinary field with learning on.
    fun learn(
        word: String,
        previous: String?,
    ) {
        if (!learning || word.isEmpty()) return
        userWords.record(word, previous, language)
    }
}
