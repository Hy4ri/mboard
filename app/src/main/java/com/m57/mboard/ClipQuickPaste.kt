package com.m57.mboard

import android.content.ClipData
import android.content.ClipDescription
import android.net.Uri
import android.view.inputmethod.EditorInfo
import java.util.Locale

/**
 * Ephemeral quick-paste candidates extracted from the primary clipboard while mboard is visible.
 * Supports verification/OTP codes (4–8 digits, ASCII or Arabic-Indic), recent copied text, and copied images.
 */
object ClipQuickPaste {
    const val FRESH_WINDOW_MS = 2 * 60_000L
    private const val MAX_PREVIEW_CHARS = 32
    private const val MAX_SCAN_CHARS = 1_000

    sealed interface Action {
        val label: String
        val contentDescription: String

        data class Code(
            val code: String,
        ) : Action {
            override val label: String = code
            override val contentDescription: String = "Paste verification code: $code"
        }

        data class Text(
            val text: String,
            override val label: String,
        ) : Action {
            override val contentDescription: String = "Paste copied text: ${text.take(80)}"
        }

        data class Image(
            val uri: Uri,
            val description: ClipDescription,
        ) : Action {
            override val label: String = "Paste image"
            override val contentDescription: String = "Paste copied image"
        }
    }

    data class Snapshot(
        val timestamp: Long,
        val text: String?,
        val code: String?,
        val imageUri: Uri?,
        val imageDescription: ClipDescription?,
    )

    fun inspect(
        clip: ClipData?,
        nowMs: Long = System.currentTimeMillis(),
    ): Snapshot? {
        if (clip == null || clip.itemCount == 0) return null
        val desc = clip.description ?: return null
        val rawStamp = desc.timestamp
        val stamp = if (rawStamp > 0L) rawStamp else nowMs
        if (rawStamp > 0L && nowMs - rawStamp > FRESH_WINDOW_MS) return null

        val item = clip.getItemAt(0) ?: return null
        val rawText = item.text?.toString()?.takeIf { it.isNotBlank() }
        val code = rawText?.let(::extractOtp)

        val uri = item.uri
        val imageDesc =
            if (uri != null && hasImageMime(desc)) {
                desc
            } else {
                null
            }

        if (rawText == null && imageDesc == null) return null
        return Snapshot(
            timestamp = stamp,
            text = rawText,
            code = code,
            imageUri = if (imageDesc != null) uri else null,
            imageDescription = imageDesc,
        )
    }

    /**
     * Build up to [maxSlots] quick-paste actions for the current editor.
     * In numeric fields, only digits (extracted OTP or a purely numeric clip) are offered.
     */
    fun actionsFor(
        snapshot: Snapshot?,
        numericField: Boolean,
        editorInfo: EditorInfo?,
        nowMs: Long = System.currentTimeMillis(),
        maxSlots: Int = 2,
    ): List<Action> {
        if (snapshot == null || nowMs - snapshot.timestamp > FRESH_WINDOW_MS) return emptyList()
        if (numericField) {
            val code =
                snapshot.code
                    ?: snapshot.text?.let(::standaloneDigits)
                    ?: return emptyList()
            return listOf(Action.Code(code))
        }
        return buildList {
            if (snapshot.imageUri != null && snapshot.imageDescription != null &&
                editorSupportsImage(editorInfo, snapshot.imageDescription)
            ) {
                add(Action.Image(snapshot.imageUri, snapshot.imageDescription))
            }
            if (snapshot.code != null && size < maxSlots) {
                add(Action.Code(snapshot.code))
            }
            val text = snapshot.text
            if (text != null && size < maxSlots) {
                val normalized = normalizeDigits(text.trim())
                if (snapshot.code == null || normalized != snapshot.code) {
                    val preview = previewLabel(text)
                    if (preview.isNotEmpty()) {
                        add(Action.Text(text, preview))
                    }
                }
            }
        }.take(maxSlots)
    }

    private fun hasImageMime(description: ClipDescription): Boolean {
        for (i in 0 until description.mimeTypeCount) {
            val mime = description.getMimeType(i).lowercase(Locale.ROOT)
            if (mime.startsWith("image/")) return true
        }
        return false
    }

    fun matchingImageMime(
        editorInfo: EditorInfo?,
        description: ClipDescription,
    ): String? {
        val accepted = editorInfo?.contentMimeTypes ?: return null
        if (accepted.isEmpty()) return null
        for (i in 0 until description.mimeTypeCount) {
            val clipMime = description.getMimeType(i)
            if (!clipMime.lowercase(Locale.ROOT).startsWith("image/")) continue
            if (accepted.any { ClipDescription.compareMimeTypes(clipMime, it) }) return clipMime
        }
        return null
    }

    private fun editorSupportsImage(
        editorInfo: EditorInfo?,
        description: ClipDescription,
    ): Boolean = matchingImageMime(editorInfo, description) != null

    fun previewLabel(text: String): String {
        val singleLine = text.trim().replace(WHITESPACE_REGEX, " ")
        return if (singleLine.length > MAX_PREVIEW_CHARS) {
            singleLine.take(MAX_PREVIEW_CHARS).trimEnd() + "…"
        } else {
            singleLine
        }
    }

    /** If the entire clip is a 4–8 digit code (optionally with one space/hyphen), return its ASCII digits. */
    fun standaloneDigits(raw: String): String? {
        val trimmed = normalizeDigits(raw.trim())
        if (STANDALONE_CODE_REGEX.matches(trimmed)) {
            val digits = trimmed.filter(Char::isDigit)
            if (digits.length in 4..8) return digits
        }
        return null
    }

    /**
     * Extract a 4–8 digit verification/OTP code from [raw].
     * - Standalone 4–8 digit clips (e.g. "482910" or "482-910") always match.
     * - Longer messages match when they contain an English or Arabic verification keyword
     *   and a 4–8 digit token not attached to a URL, phone number, currency, date, or time.
     */
    fun extractOtp(raw: String): String? {
        if (raw.length > MAX_SCAN_CHARS) return null
        standaloneDigits(raw)?.let { return it }

        val normalized = normalizeDigits(raw)
        val lower = normalized.lowercase(Locale.ROOT)
        if (!hasOtpKeyword(lower)) return null

        val cleaned = URL_REGEX.replace(normalized, " ")
        val candidates = ArrayList<Candidate>()
        for (match in CODE_CANDIDATE_REGEX.findAll(cleaned)) {
            val token = match.value
            val start = match.range.first
            val endExclusive = match.range.last + 1
            if (isFalsePositiveContext(cleaned, start, endExclusive, token)) continue
            val digits = token.filter(Char::isDigit)
            if (digits.length !in 4..8) continue
            // Skip plain 4-digit calendar years (1990..2039) inside longer prose unless preceded by ':' or "is".
            if (digits.length == 4 && digits.toIntOrNull() in 1990..2039 && !isImmediatelySignaled(cleaned, start)) {
                continue
            }
            candidates.add(Candidate(digits, start, isImmediatelySignaled(cleaned, start)))
        }
        if (candidates.isEmpty()) return null
        // Prefer candidates immediately preceded by "is", ":", "#" or an OTP keyword, or 6-digit codes.
        return candidates
            .sortedWith(
                compareByDescending<Candidate> { it.signaled }
                    .thenByDescending { it.digits.length == 6 }
                    .thenBy { it.position },
            ).first()
            .digits
    }

    private data class Candidate(
        val digits: String,
        val position: Int,
        val signaled: Boolean,
    )

    private fun isImmediatelySignaled(
        text: String,
        start: Int,
    ): Boolean {
        val before = text.substring(0, start).trimEnd()
        if (before.endsWith(':') || before.endsWith('#') || before.endsWith('=') || before.endsWith("is", ignoreCase = true) ||
            before.endsWith("هو") || before.endsWith("هي")
        ) {
            return true
        }
        val window = before.takeLast(28).lowercase(Locale.ROOT)
        return OTP_KEYWORDS.any { it in window }
    }

    private fun isFalsePositiveContext(
        text: String,
        start: Int,
        endExclusive: Int,
        token: String,
    ): Boolean {
        val prev = if (start > 0) text[start - 1] else ' '
        val next = if (endExclusive < text.length) text[endExclusive] else ' '
        // Part of a longer alphanumeric identifier, path, decimal, time, date, or phone number with '+'.
        if (prev.isLetterOrDigit() || next.isLetterOrDigit()) return true
        // Allow "G-123456" (Google verification codes) while rejecting ordinary hyphenated numbers.
        val isGooglePrefix =
            prev == '-' && start >= 2 && (text[start - 2] == 'G' || text[start - 2] == 'g') &&
                (start == 2 || !text[start - 3].isLetterOrDigit())
        if ((prev == '-' && !isGooglePrefix) || next == '-') return true
        if (prev in "+$€£¥/." || next in "$€£¥/%:") return true
        // Skip currency/amount suffixes right after the number (e.g. "1500 JOD", "2000 USD").
        val afterTrimmed = text.substring(endExclusive).trimStart()
        if (CURRENCY_PREFIX_REGEX.containsMatchIn(afterTrimmed)) return true
        val beforeTrimmed = text.substring(0, start).trimEnd()
        if (CURRENCY_SUFFIX_REGEX.containsMatchIn(beforeTrimmed)) return true
        // If separated by a space ("123 456"), require both halves to be 3-4 digits.
        if (' ' in token) {
            val parts = token.split(' ')
            if (parts.size != 2 || parts[0].length != parts[1].length || parts[0].length !in 3..4) return true
        }
        return false
    }

    private fun hasOtpKeyword(lowerText: String): Boolean =
        OTP_WORD_REGEX.containsMatchIn(lowerText) || OTP_ARABIC_KEYWORDS.any { it in lowerText }

    /** Convert Arabic-Indic (٠-٩) and Eastern Arabic-Indic (۰-۹) digits to ASCII 0-9. */
    fun normalizeDigits(input: String): String =
        buildString(input.length) {
            for (ch in input) {
                when (ch) {
                    in '\u0660'..'\u0669' -> append(('0'.code + (ch.code - 0x0660)).toChar())
                    in '\u06F0'..'\u06F9' -> append(('0'.code + (ch.code - 0x06F0)).toChar())
                    else -> append(ch)
                }
            }
        }

    private val WHITESPACE_REGEX = Regex("\\s+")
    private val URL_REGEX = Regex("https?://\\S+|www\\.\\S+", RegexOption.IGNORE_CASE)
    private val STANDALONE_CODE_REGEX = Regex("^\\d{4,8}$|^\\d{3,4}[- ]\\d{3,4}$")
    private val CODE_CANDIDATE_REGEX = Regex("\\d{3,4}-\\d{3,4}|\\d{4,8}")
    private val CURRENCY_PREFIX_REGEX =
        Regex("^(?:usd|eur|gbp|jod|sar|aed|egp|qar|kwd|bhd|omr|دينار|ريال|دولار|درهم|جنيه)\\b", RegexOption.IGNORE_CASE)
    private val CURRENCY_SUFFIX_REGEX =
        Regex("(?:usd|eur|gbp|jod|sar|aed|egp|rs|inr)\\s*$", RegexOption.IGNORE_CASE)

    private val OTP_WORD_REGEX =
        Regex(
            "\\b(?:code|otp|verification|verify|passcode|pin|one-time|onetime|security|auth|" +
                "authentication|login|signin|sign-in|confirm|confirmation|token)\\b|" +
                "\\bG-\\d{4,8}\\b",
            RegexOption.IGNORE_CASE,
        )

    private val OTP_ARABIC_KEYWORDS =
        listOf(
            "رمز",
            "كود",
            "تحقق",
            "التحقق",
            "تأكيد",
            "التأكيد",
            "دخول",
            "الدخول",
            "سري",
            "السري",
            "تفعيل",
            "التفعيل",
            "امان",
            "أمان",
            "الأمان",
            "الامان",
        )

    private val OTP_KEYWORDS =
        listOf(
            "code",
            "otp",
            "verification",
            "verify",
            "passcode",
            "pin",
            "security",
            "login",
            "confirm",
            "token",
        ) + OTP_ARABIC_KEYWORDS
}
