package com.m57.mboard

import android.text.InputType
import android.view.inputmethod.EditorInfo

/** One policy for suggestions and its explanation; diagnostics never need the user's text. */
data class SuggestionPolicy(
    val sensitive: Boolean,
    val allowed: Boolean,
    val numeric: Boolean,
    val reason: Reason,
) {
    val explanation get() = reason.explanation
    val password get() = reason == Reason.PASSWORD

    enum class Reason(
        val explanation: String,
    ) {
        PASSWORD("Password field · word suggestions paused"),
        PRIVATE("No personalized learning · static completions only"),
        NON_TEXT("This input field does not request word suggestions"),
        APP_BLOCKED("App disabled word suggestions for this field"),
        ADDRESS("Email or URL field · word suggestions paused"),
        AVAILABLE("Word completions available"),
    }

    companion object {
        fun clearPasswordTransition(
            previousType: Int,
            nextType: Int,
        ): Boolean = previousType != nextType && (classify(previousType, 0).password || classify(nextType, 0).password)

        fun classify(
            inputType: Int,
            imeOptions: Int,
        ): SuggestionPolicy {
            val inputClass = inputType and InputType.TYPE_MASK_CLASS
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            val password =
                (
                    inputClass == InputType.TYPE_CLASS_TEXT && variation in
                        setOf(
                            InputType.TYPE_TEXT_VARIATION_PASSWORD,
                            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                        )
                ) || (inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
            val noLearning = imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
            val numeric = inputClass in setOf(InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME)
            // Apps like Instagram set NO_SUGGESTIONS together with AUTO_CORRECT; the explicit request wins.
            val requestsHelp = inputType and (InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE) != 0
            val reason =
                when {
                    password -> Reason.PASSWORD

                    inputClass != InputType.TYPE_CLASS_TEXT -> Reason.NON_TEXT

                    inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0 && !requestsHelp -> Reason.APP_BLOCKED

                    variation in
                        setOf(
                            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                            InputType.TYPE_TEXT_VARIATION_URI,
                        ) -> Reason.ADDRESS

                    noLearning -> Reason.PRIVATE

                    else -> Reason.AVAILABLE
                }
            return SuggestionPolicy(password || noLearning, reason == Reason.AVAILABLE || reason == Reason.PRIVATE, numeric, reason)
        }
    }
}
