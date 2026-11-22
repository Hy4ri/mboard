package com.m57.bench

import android.content.Context
import com.m57.mboard.Language
import com.m57.mboard.PredictionTrail
import com.m57.mboard.Script
import com.m57.mboard.StripPlanner
import com.m57.mboard.UserWords
import com.m57.mboard.WordDictionary
import com.m57.mboard.WordSuggestions

/**
 * Desktop bench wrapper around [StripPlanner] for an editor whose caret sits at the end of `text`.
 *
 * Dictionary loading, ranking, prediction, strip planning and learning are all the keyboard's real classes.
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
    val planner = StripPlanner(words, { userWords }, predictionTrail, rejectedCorrections)

    /** The bundled word list, to tell real-word typos (buy → but) from non-words. */
    val dictionary: WordDictionary =
        context.assets
            .open(language.wordsAsset)
            .bufferedReader()
            .use { WordDictionary.read(it, language) }

    init {
        words.load(language) {}
        if (arabizi && language.script == Script.LATIN) {
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
        internal val raw: StripPlanner.CaretContext,
    ) {
        val prefix: String get() = raw.prefix
        val idle: Boolean get() = raw.idle
        val contextWords: List<String> get() = raw.contextWords
        val sentenceStart: Boolean get() = raw.sentenceStart
    }

    class Shown(
        val values: List<String>,
        /** First value is an auto-correction that space will apply. */
        val highlight: Boolean,
    )

    /** Keyboard shift state (auto-capitalize at a sentence start); the replay bench keeps it off. */
    var shifted = false

    fun policy(numeric: Boolean = false): StripPlanner.StripPolicy =
        StripPlanner.StripPolicy(
            language = language,
            dialect = dialect,
            autoCorrect = autoCorrect,
            useLearnedHistory = learning,
            arabizi = arabizi,
            numeric = numeric,
            shifted = shifted,
        )

    fun wordChar(char: Char): Boolean = StripPlanner.wordChar(char)

    fun context(text: String): Caret = Caret(planner.context(text))

    fun autoCorrection(
        typed: String,
        wordStart: Boolean,
        contextWords: List<String> = emptyList(),
    ): String? = planner.autoCorrection(typed, wordStart, contextWords, policy())

    fun show(
        c: Caret,
        numeric: Boolean = false,
    ): Shown {
        val plan = planner.plan(c.raw, policy(numeric))
        return Shown(plan.suggestions, plan.highlightFirst)
    }

    fun rejectCorrection(
        typed: String,
        corrected: String,
    ) = planner.rejectCorrection(typed, corrected, language, learning)

    fun acceptCorrection(
        typed: String,
        corrected: String,
    ) = planner.acceptCorrection(typed, corrected, language, learning)

    fun learn(
        word: String,
        previous: String?,
    ) = planner.learn(word, previous, language, learning)
}
