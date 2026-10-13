package com.m57.mboard

/** Arabic word lists the dictionary consults beside its frequency list. */
object ArabicWords {
    /** Frequent misspellings that the subtitle corpus lists as words, so edit rules cannot catch them. */
    val MISSPELLINGS: Map<String, String> =
        mapOf(
            "ذالك" to "ذلك",
            "كذالك" to "كذلك",
            "بذالك" to "بذلك",
            "لذالك" to "لذلك",
            "هاذه" to "هذه",
            "هاكذا" to "هكذا",
            "لاكن" to "لكن",
            "ولاكن" to "ولكن",
            "اللة" to "الله",
            "مسؤل" to "مسؤول",
            "مسئول" to "مسؤول",
            "لاكنه" to "لكنه",
            "لاكني" to "لكني",
        )
}
