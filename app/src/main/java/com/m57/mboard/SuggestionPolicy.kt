package com.m57.mboard

import android.text.InputType

/** Apps' NO_SUGGESTIONS flags never override the user's keyboard preferences. */
data class SuggestionPolicy(
    val numeric: Boolean,
    val reason: Reason,
) {
    val password get() = reason == Reason.PASSWORD

    enum class Reason { PASSWORD, ADDRESS, AVAILABLE }

    companion object {
        fun clearPasswordTransition(
            previousType: Int,
            nextType: Int,
        ): Boolean = previousType != nextType && (classify(previousType).password || classify(nextType).password)

        fun classify(inputType: Int): SuggestionPolicy {
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
            val numeric = inputClass in setOf(InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME)
            val reason =
                when {
                    password -> Reason.PASSWORD

                    inputClass == InputType.TYPE_CLASS_TEXT && variation in
                        setOf(
                            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                            InputType.TYPE_TEXT_VARIATION_URI,
                        )
                    -> Reason.ADDRESS

                    else -> Reason.AVAILABLE
                }
            return SuggestionPolicy(numeric, reason)
        }
    }
}
