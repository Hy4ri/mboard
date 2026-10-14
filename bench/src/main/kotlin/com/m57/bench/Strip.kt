package com.m57.bench

import android.content.Context
import com.m57.mboard.Language
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
) {
    val words = WordSuggestions(context)
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
        val before = text.takeLast(65)
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
                .takeLast(8)
        return Caret(result, idle, recent, sentenceStart)
    }

    private fun matchCase(
        word: String,
        source: String,
    ) = WordDictionary.displayWord(word, source, language, false, false)

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
        return if (correction == typed) null else correction
    }

    // MboardService.renderSuggestions, ordinary text field, no saved phrases, emoji suggestions off.
    fun show(c: Caret): Shown {
        if (c.idle) {
            val predicted = words.predict(c.contextWords, language, dialect, learnedNext(c.contextWords))
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
        val leading = autoCorrection ?: hint
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
        val others =
            (listOfNotNull(leading) + ranked)
                .filter { it != typed }
                .distinctBy { it.lowercase(Locale.ROOT) }
        val wordChoices = if (others.size < 3 && typed.length >= 2) others + typed else others
        return Shown(wordChoices.take(3), autoCorrection != null)
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
