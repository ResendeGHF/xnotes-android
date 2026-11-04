package com.xnotes.core.search

/** Fallback primitives on native until the host provides its own; ASCII matches, nothing folds. */
actual val defaultSearchUnicode: SearchUnicode = AsciiUnicode
