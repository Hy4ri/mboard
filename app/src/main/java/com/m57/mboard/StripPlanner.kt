package com.m57.mboard

import java.util.Locale

/**
 * Pure-Kotlin suggestion-strip planner shared by [MboardService] and the desktop replay bench.
 *
 * Takes plain surrounding text and policy flags, computes the caret context, auto-correction,
 * ranked strip choices, and strip mode/status, and routes word-learning updates to [UserWords].
 */
class StripPlanner(
    private val words: WordSuggestions,
    /** Read on every use: the service swaps in a fresh store when learned words are deleted. */
    private val currentUserWords: () -> UserWords,
    private val predictionTrail: PredictionTrail,
    private val rejectedCorrections: MutableSet<String> = HashSet(),
) {
    private val userWords get() = currentUserWords()

    data class EditorText(
        val before: String?,
        val after: String?,
        val hasEditor: Boolean = true,
        val hasSelection: Boolean = false,
    )

    enum class CaretStatus {
        NO_EDITOR,
        SELECTION_ACTIVE,
        NO_SURROUNDING_TEXT,
        WORD_TOO_LONG,
        IDLE_HINT,
        NO_MATCH,
        ;

        fun toStripStatus(): StripStatus =
            when (this) {
                NO_EDITOR -> StripStatus.NO_EDITOR
                SELECTION_ACTIVE -> StripStatus.SELECTION_ACTIVE
                NO_SURROUNDING_TEXT -> StripStatus.NO_SURROUNDING_TEXT
                WORD_TOO_LONG -> StripStatus.WORD_TOO_LONG
                IDLE_HINT -> StripStatus.IDLE_HINT
                NO_MATCH -> StripStatus.NO_MATCH
            }
    }

    data class CaretContext(
        val prefix: String,
        val status: CaretStatus,
        val idle: Boolean = false,
        val contextWords: List<String> = emptyList(),
        val sentenceStart: Boolean = false,
        /** Word letters right after the caret: the caret sits inside or at the start of an existing word. */
        val suffix: String = "",
        /** Whether anything follows the word, so a picked suggestion needs no trailing space. */
        val textAfterWord: Boolean = false,
    ) {
        /** The whole word the caret touches; suggestions are for it and a pick replaces all of it. */
        val word: String get() = prefix + suffix

        val editingInside: Boolean get() = suffix.isNotEmpty()
    }

    data class SavedAddresses(
        val typed: String,
        val matches: List<String>,
    )

    data class StripPolicy(
        val language: Language,
        val dialect: Boolean,
        val suggestionsEnabled: Boolean = true,
        val autoCorrect: Boolean = true,
        val useLearnedHistory: Boolean = true,
        val arabizi: Boolean = false,
        val numeric: Boolean = false,
        val password: Boolean = false,
        val shifted: Boolean = false,
        val capsLocked: Boolean = false,
    )

    enum class StripMode {
        NUMERIC,
        BLOCKED,
        UNAVAILABLE,
        PASSWORD,
        ADDRESSES,
        NEXT,
        COMPLETE,
        NONE,
    }

    enum class StripStatus {
        NUMBER_FIELD,
        SUGGESTIONS_OFF,
        LOADING_DICTIONARY,
        DICTIONARY_FAILED,
        SUGGESTIONS_UNAVAILABLE,
        PREPARING,
        STATIC_SUGGESTIONS,
        SAVED_ADDRESSES,
        NO_EDITOR,
        SELECTION_ACTIVE,
        NO_SURROUNDING_TEXT,
        WORD_TOO_LONG,
        IDLE_HINT,
        NO_MATCH,
    }

    data class StripPlan(
        val mode: StripMode,
        val word: String,
        val context: CaretContext?,
        val suggestions: List<String>,
        val autoCorrection: String?,
        val highlightFirst: Boolean,
        val status: StripStatus,
        val addresses: Boolean = false,
        val shieldNote: Boolean = true,
    )

    fun context(editor: EditorText): CaretContext {
        if (!editor.hasEditor) return CaretContext("", CaretStatus.NO_EDITOR)
        if (editor.hasSelection) return CaretContext("", CaretStatus.SELECTION_ACTIVE)
        val after = editor.after ?: return CaretContext("", CaretStatus.NO_SURROUNDING_TEXT)
        val before = editor.before ?: return CaretContext("", CaretStatus.NO_SURROUNDING_TEXT)

        val result = before.takeLastWhile(::wordChar)
        val suffix = after.takeWhile(::wordChar)
        if (result.length + suffix.length > MAX_WORD_CHARS) return CaretContext("", CaretStatus.WORD_TOO_LONG)
        val idle = result.isEmpty() && before.lastOrNull()?.isWhitespace() != false && after.firstOrNull()?.isWhitespace() != false
        val beforeWord = before.dropLast(result.length).trimEnd(' ', '\t')
        val sentenceStart = beforeWord.isEmpty() || beforeWord.last() in ".!?؟\n"
        // Only the current sentence contributes context; punctuation and line breaks reset it.
        val recent =
            before
                .dropLast(result.length)
                .takeLastWhile { wordChar(it) || it == ' ' || it == '\t' }
                .trim()
                .split(WHITESPACE)
                .filter { it.isNotEmpty() }
                // The tables read two words; the rest lets prediction see a repeating phrase.
                .takeLast(CONTEXT_WORDS)
        return CaretContext(
            prefix = result,
            status = if (result.isEmpty() && suffix.isEmpty()) CaretStatus.IDLE_HINT else CaretStatus.NO_MATCH,
            idle = idle,
            contextWords = recent,
            sentenceStart = sentenceStart,
            suffix = suffix,
            textAfterWord = after.length > suffix.length,
        )
    }

    fun context(text: String): CaretContext = context(EditorText(before = text.takeLast(CONTEXT_CHARS), after = ""))

    private fun learnedNext(
        contextWords: List<String>,
        policy: StripPolicy,
    ): Map<String, Int> =
        if (!policy.useLearnedHistory) {
            emptyMap()
        } else {
            contextWords.lastOrNull()?.let { userWords.next(it, policy.language) }.orEmpty()
        }

    private fun matchCase(
        word: String,
        source: String,
        policy: StripPolicy,
    ): String = WordDictionary.displayWord(word, source, policy.language, policy.shifted, policy.capsLocked)

    fun autoCorrection(
        typed: String,
        wordStart: Boolean,
        contextWords: List<String> = emptyList(),
        policy: StripPolicy,
    ): String? {
        if (!policy.autoCorrect || typed.isEmpty() || typed in rejectedCorrections || policy.password) return null
        if (userWords.knows(typed, policy.language)) return null
        if (words.state(policy.language) != WordSuggestions.State.READY) return null
        if (policy.language.cased && typed.first().isUpperCase() && !wordStart && typed != "i") return null
        val correction =
            words
                .correction(typed, policy.language, contextWords, policy.dialect, learnedNext(contextWords, policy))
                ?.let { matchCase(it, typed, policy) } ?: return null
        return if (correction == typed || userWords.rejects(typed, correction, policy.language)) null else correction
    }

    fun plan(
        policy: StripPolicy,
        readEditor: () -> EditorText,
        readAddresses: () -> SavedAddresses? = { null },
        readPhrases: () -> List<String> = { emptyList() },
        emojiForWord: ((String) -> String?)? = null,
    ): StripPlan {
        val early = planBeforeCaret(policy, readAddresses)
        if (early != null) return early
        return planForCaret(context(readEditor()), policy, readPhrases, emojiForWord)
    }

    fun plan(
        context: CaretContext,
        policy: StripPolicy,
        readAddresses: () -> SavedAddresses? = { null },
        readPhrases: () -> List<String> = { emptyList() },
        emojiForWord: ((String) -> String?)? = null,
    ): StripPlan {
        val early = planBeforeCaret(policy, readAddresses)
        if (early != null) return early
        return planForCaret(context, policy, readPhrases, emojiForWord)
    }

    private fun planBeforeCaret(
        policy: StripPolicy,
        readAddresses: () -> SavedAddresses?,
    ): StripPlan? {
        // Numbers, phone and date fields take digits, not words: an honest empty strip.
        if (policy.numeric) {
            return StripPlan(
                mode = StripMode.NUMERIC,
                word = "",
                context = null,
                suggestions = emptyList(),
                autoCorrection = null,
                highlightFirst = false,
                status = StripStatus.NUMBER_FIELD,
            )
        }
        if (!policy.suggestionsEnabled) {
            // Email/URL fields skip dictionary words but still offer the user's saved email addresses.
            val addresses = readAddresses()
            if (addresses != null && addresses.matches.isNotEmpty()) {
                return StripPlan(
                    mode = StripMode.ADDRESSES,
                    word = addresses.typed,
                    context = null,
                    suggestions = addresses.matches,
                    autoCorrection = null,
                    highlightFirst = false,
                    status = StripStatus.SUGGESTIONS_OFF,
                    addresses = true,
                    shieldNote = false,
                )
            }
            return StripPlan(
                mode = StripMode.BLOCKED,
                word = "",
                context = null,
                suggestions = emptyList(),
                autoCorrection = null,
                highlightFirst = false,
                status = StripStatus.SUGGESTIONS_OFF,
            )
        }
        val state = words.state(policy.language)
        if (state != WordSuggestions.State.READY) {
            val status =
                when (state) {
                    WordSuggestions.State.LOADING -> StripStatus.LOADING_DICTIONARY
                    WordSuggestions.State.FAILED -> StripStatus.DICTIONARY_FAILED
                    WordSuggestions.State.CLOSED -> StripStatus.SUGGESTIONS_UNAVAILABLE
                    else -> StripStatus.PREPARING
                }
            return StripPlan(
                mode = StripMode.UNAVAILABLE,
                word = "",
                context = null,
                suggestions = emptyList(),
                autoCorrection = null,
                highlightFirst = false,
                status = status,
            )
        }
        if (policy.password) {
            return StripPlan(
                mode = StripMode.PASSWORD,
                word = "",
                context = null,
                suggestions = words.commonWords(policy.language).take(3),
                autoCorrection = null,
                highlightFirst = false,
                status = StripStatus.STATIC_SUGGESTIONS,
            )
        }
        val addresses = readAddresses()
        if (addresses != null && addresses.matches.isNotEmpty()) {
            return StripPlan(
                mode = StripMode.ADDRESSES,
                word = addresses.typed,
                context = null,
                suggestions = addresses.matches,
                autoCorrection = null,
                highlightFirst = false,
                status = StripStatus.SAVED_ADDRESSES,
                addresses = true,
                shieldNote = false,
            )
        }
        return null
    }

    private fun planForCaret(
        context: CaretContext,
        policy: StripPolicy,
        readPhrases: () -> List<String>,
        emojiForWord: ((String) -> String?)?,
    ): StripPlan {
        if (context.idle) {
            val predicted =
                words
                    .predict(context.contextWords, policy.language, policy.dialect, learnedNext(context.contextWords, policy))
                    .let { predictionTrail.filter(context.contextWords, it) }
            return StripPlan(
                mode = StripMode.NEXT,
                word = "",
                context = context,
                suggestions = predicted.map { matchCase(it, "", policy) }.take(3),
                autoCorrection = null,
                highlightFirst = false,
                status = context.status.toStripStatus(),
            )
        }
        // The caret may sit inside an earlier word: suggest for that whole word, not just the part before it.
        val typed = context.word
        if (typed.isEmpty()) {
            return StripPlan(
                mode = StripMode.NONE,
                word = "",
                context = context,
                suggestions = emptyList(),
                autoCorrection = null,
                highlightFirst = false,
                status = context.status.toStripStatus(),
            )
        }
        val editingInside = context.editingInside
        // Space only auto-corrects at a word's end, so inside a word the correction is offered, not highlighted.
        val autoCorrection = autoCorrection(typed, context.sentenceStart, context.contextWords, policy)
        val hint =
            if (autoCorrection == null && policy.autoCorrect && !userWords.knows(typed, policy.language)) {
                words.likelyIntended(typed, policy.language)?.let { matchCase(it, typed, policy) }
            } else {
                null
            }
        // Space missed between two words ("fixxit" → "fix it"): a suggestion only, never applied on space.
        val splitHint =
            if (!userWords.knows(typed, policy.language)) {
                words.splitSuggestion(typed, policy.language, context.contextWords, policy.dialect)
            } else {
                null
            }
        // A saved phrase starting with the typed word follows the correction; tapping it replaces the word.
        val phrase =
            if (policy.password || typed.length < 2) {
                null
            } else {
                readPhrases().firstOrNull { it.length > typed.length && it.startsWith(typed, ignoreCase = true) }
            }
        // Learned words are ranked with the dictionary by how likely they are here.
        val ranked =
            words
                .rank(
                    typed,
                    context.contextWords,
                    policy.language,
                    policy.dialect,
                    learnedNext(context.contextWords, policy),
                    if (!policy.useLearnedHistory) emptyMap() else userWords.completions(typed, policy.language),
                ).map { matchCase(it, typed, policy) }
        // A split leads only when no one-word typo candidate (fuzzy, two-edit, rule) is ranked, or the pair is very common.
        val split = splitHint?.text?.let { matchCase(it, typed, policy) }
        val oneWordTypo = ranked.any { !it.startsWith(typed, ignoreCase = true) }
        val leading = autoCorrection ?: hint ?: split?.takeIf { splitHint?.strong == true || !oneWordTypo }
        val others =
            (listOfNotNull(leading, phrase) + ranked)
                .filter { it != typed }
                .distinctBy { it.lowercase(Locale.ROOT) }
                .toMutableList()
        if (split != null && split != leading && others.none { it.equals(split, ignoreCase = true) }) {
            others.add(minOf(1, others.size), split)
        }
        // The word as typed always keeps a slot, right after a correction or hint, so it can be kept with one tap
        // (tapping it finishes the word with a space and no correction). Single letters are left out.
        val keepAt = if (leading != null && leading != typed) 1 else 0
        val wordChoices = if (typed.length >= 2) others.take(keepAt) + typed + others.drop(keepAt) else others
        // Franco typing on the English layout may also offer Arabic-script readings (suggest-only).
        val readings = if (policy.arabizi && !editingInside) words.arabizi(typed, policy.language, 2) else emptyList()
        val shown = Arabizi.place(wordChoices, typed, readings, lastSlotOnly = words.isArabiziListed(typed))
        // A matching emoji takes the last slot so the correction and best completion stay put.
        val emoji = if (emojiForWord != null && !policy.password) emojiForWord(typed) else null
        val values = if (emoji == null) shown.take(3) else shown.take(2) + emoji
        return StripPlan(
            mode = StripMode.COMPLETE,
            word = typed,
            context = context,
            suggestions = values,
            autoCorrection = autoCorrection,
            highlightFirst = autoCorrection != null && !editingInside,
            status = context.status.toStripStatus(),
        )
    }

    fun learn(
        word: String,
        previous: String?,
        language: Language,
        learningAllowed: Boolean,
    ) {
        if (!learningAllowed || word.isEmpty()) return
        userWords.record(word, previous, language)
    }

    fun rejectCorrection(
        typed: String,
        corrected: String,
        language: Language,
        learningAllowed: Boolean,
    ) {
        if (!learningAllowed) return
        userWords.reject(typed, corrected, language)
        userWords.save()
    }

    fun acceptCorrection(
        typed: String,
        corrected: String,
        language: Language,
        learningAllowed: Boolean,
    ) {
        if (!learningAllowed || !userWords.rejects(typed, corrected, language)) return
        userWords.acceptCorrection(typed, corrected, language)
        userWords.save()
    }

    companion object {
        const val CONTEXT_CHARS = 120
        const val CONTEXT_WORDS = 16
        const val MAX_WORD_CHARS = 64
        private val WHITESPACE = Regex("\\s+")

        fun wordChar(char: Char): Boolean =
            char.isLetterOrDigit() || Character.getType(char) == Character.NON_SPACING_MARK.toInt() || char == '\''
    }
}
