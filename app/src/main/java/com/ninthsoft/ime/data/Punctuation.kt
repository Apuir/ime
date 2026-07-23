package com.ninthsoft.ime.data

sealed class Punctuation {

    companion object {
        fun from(punctuation: String): Punctuation {
            return when (punctuation) {
                "full-width" -> FullWidth
                else -> HalfWidth
            }
        }
    }

    data object FullWidth : Punctuation()
    data object HalfWidth : Punctuation()
}