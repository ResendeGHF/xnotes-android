package com.xnotes.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.xnotes.R

/**
 * The "Notes" wordmark family (sidebar title, empty-home header, About title).
 *
 * Michroma Regular (SIL Open Font License 1.1, see
 * `app/src/main/assets/fonts-licenses/Michroma-OFL.txt`): a modern, minimalist
 * geometric display face with the same squared-futuristic feel as Mokoto
 * Regular. Mokoto itself is not SIL-licensed, so it cannot be bundled; Michroma
 * is the closest open-licensed match (single 400 weight, hence always used with
 * [androidx.compose.ui.text.font.FontWeight.Normal] plus wide letter spacing).
 */
val NotesBrandFont: FontFamily = FontFamily(Font(R.font.michroma_regular))
