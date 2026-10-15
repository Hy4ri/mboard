package com.m57.mboard

/** Arabic word lists the dictionary consults beside its frequency list. */
object ArabicWords {
    /** Observed technical/dialect spellings absent from the bundled lexical sources. */
    val KEEP_TYPED: Set<String> = setOf("عامد", "يعامد", "بعطي", "بعطيك", "كويز", "كويزات", "سيفتي")

    /** Source-attested nouns/تعاملك and their productive attached forms; minimum lexical score only. */
    val SUPPLEMENTAL: Set<String> = setOf("آراء", "مرفقات", "المرفقات", "إخطار", "إفراغ", "التجميع", "تعاملك", "لتعاملك")

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
