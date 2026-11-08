package com.m57.mboard

import android.content.Context

fun Language.localizedName(context: Context): String =
    when (code) {
        "en" -> context.getString(R.string.language_en)
        "ar" -> context.getString(R.string.language_ar)
        else -> name
    }

fun Dialect.localizedTitle(context: Context): String =
    when (settingKey) {
        "jordanian" -> context.getString(R.string.dialect_jordanian_title)
        else -> title
    }

fun Dialect.localizedSummary(context: Context): String =
    when (settingKey) {
        "jordanian" -> context.getString(R.string.dialect_jordanian_summary)
        else -> summary
    }

fun joinLanguageLabels(
    context: Context,
    labels: List<String>,
): String =
    when (labels.size) {
        0 -> {
            ""
        }

        1 -> {
            labels[0]
        }

        2 -> {
            context.getString(R.string.languages_and, labels[0], labels[1])
        }

        else -> {
            context.getString(
                R.string.languages_and,
                labels.dropLast(1).joinToString(", "),
                labels.last(),
            )
        }
    }
