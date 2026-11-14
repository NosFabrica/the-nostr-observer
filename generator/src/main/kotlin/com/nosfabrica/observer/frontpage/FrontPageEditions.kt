/*
 * Copyright (c) 2025 Vitor Pamplona
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.nosfabrica.observer.frontpage

import kotlinx.serialization.Serializable

/** What the reader sees about a stored edition before opening it, and on its chips once open. */
@Serializable
data class SavedEdition(
    /** The local day it was printed, `yyyy-MM-dd`: one edition per day, the newest wins. */
    val day: String,
    val code: String,
    val printedAt: Long,
    val model: String,
    val tokens: Long? = null,
    /** In US dollars, from the provider's own prices; null when it lists none. */
    val costUsd: Double? = null,
    val quotes: Int = 0,
    val posts: Int = 0,
    /** The page's first headline, for the "ready" notification and the history. */
    val headline: String? = null,
    /** What resolve removed: the visible edge of somebody trying to edit the paper. */
    val removed: List<String> = emptyList(),
    /** The digest's size, for the next print's cost estimate. */
    val digestChars: Int = 0,
    val rounds: Int = 1,
    /** Hex sha256 of the page: its blob on the reader's Observer site. Null on editions kept before it. */
    val sha256: String? = null,
    /** On the reader's Observer site: published from here, or read back from it. */
    val published: Boolean = false,
    /** Printed by the runner the reader's schedule names (their Assistant), read from its own Observer site. */
    val byRunner: Boolean = false,
    /** The Kitchen's recipe this edition ran, as its address ([recipeAddress]): the next editions don't offer it again. */
    val recipe: String? = null,
)
