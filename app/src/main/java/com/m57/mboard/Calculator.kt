package com.m57.mboard

import java.math.BigDecimal

/** A tiny expression parser, not an evaluator: no scripts, identifiers, or network access. */
object Calculator {
    fun calculate(input: String): String? {
        if (input.isBlank() || input.length > 200) return null
        return try {
            val value = Parser(input).parse()
            if (!value.isFinite() || kotlin.math.abs(value) > 1e15) {
                null
            } else {
                BigDecimal
                    .valueOf(value)
                    .setScale(8, java.math.RoundingMode.HALF_UP)
                    .stripTrailingZeros()
                    .toPlainString()
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private class Parser(
        input: String,
    ) {
        private val source =
            input
                .map { char ->
                    when (char) {
                        in '٠'..'٩' -> '0' + (char - '٠')
                        '×' -> '*'
                        '÷' -> '/'
                        '−' -> '-'
                        '٫' -> '.'
                        else -> char
                    }
                }.joinToString("")
                .filterNot(Char::isWhitespace)
        private var index = 0
        private var depth = 0

        fun parse(): Double {
            val result = expression()
            require(index == source.length)
            return result
        }

        private fun expression(): Double {
            var value = term()
            while (true) {
                value =
                    when {
                        eat('+') -> value + term()
                        eat('-') -> value - term()
                        else -> return value
                    }
            }
        }

        private fun term(): Double {
            var value = factor()
            while (true) {
                value =
                    when {
                        eat('*') -> {
                            value * factor()
                        }

                        eat('/') -> {
                            val divisor = factor()
                            require(divisor != 0.0)
                            value / divisor
                        }

                        else -> {
                            return value
                        }
                    }
            }
        }

        private fun factor(): Double {
            require(++depth <= 32)
            val value =
                when {
                    eat('+') -> {
                        factor()
                    }

                    eat('-') -> {
                        -factor()
                    }

                    eat('(') -> {
                        expression().also { require(eat(')')) }
                    }

                    else -> {
                        val start = index
                        while (index < source.length && (source[index].isDigit() || source[index] == '.')) index++
                        require(index > start)
                        source.substring(start, index).toDoubleOrNull() ?: throw IllegalArgumentException()
                    }
                }
            depth--
            return if (eat('%')) value / 100.0 else value
        }

        private fun eat(char: Char): Boolean {
            if (source.getOrNull(index) != char) return false
            index++
            return true
        }
    }
}
