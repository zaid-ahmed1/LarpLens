package com.larplens.ocr

/** One line of OCR output. [height] is the line's box height in pixels (bigger = bigger writing). */
data class OcrLine(val text: String, val top: Int, val height: Int)

/**
 * Turns raw OCR lines from a name tag into the name.
 *
 * - Drops sticker template text ("HELLO", "my name is", ...)
 * - Keeps only letters, spaces, hyphens and apostrophes
 * - The name is the biggest writing on the tag; a second line written about as big (e.g. a
 *   surname stacked under the first name) is joined on, top to bottom
 */
object NameExtractor {
  private val TEMPLATE =
      listOf("hello my name is", "hello", "my name is", "name", "hi i am", "hi im", "i am")
          .map { it.replace(" ", "") }
          .toSet()

  private val NOT_NAME_CHARS = Regex("[^\\p{L}\\s'-]")
  private val SPACES = Regex("\\s+")

  /** Minimum size, relative to the biggest line, for another line to count as part of the name. */
  private const val SAME_SIZE = 0.65f

  fun extract(lines: List<OcrLine>): String? {
    val candidates =
        lines
            .map { it.copy(text = clean(it.text)) }
            .filter { it.text.any(Char::isLetter) && !isTemplate(it.text) }
    if (candidates.isEmpty()) return null
    val biggest = candidates.maxOf { it.height }
    return candidates
        .filter { it.height >= biggest * SAME_SIZE }
        .sortedBy { it.top }
        .joinToString(" ") { it.text }
        .let { SPACES.replace(it, " ").trim() }
        .takeIf { it.count(Char::isLetter) >= 2 }
  }

  /** Key used to compare readings across frames ("Zaid", "ZAID", "zaid." all agree). */
  fun normalize(name: String): String = name.lowercase().filter(Char::isLetter)

  private fun clean(text: String): String = SPACES.replace(NOT_NAME_CHARS.replace(text, " "), " ").trim()

  private fun isTemplate(text: String): Boolean {
    val squashed = text.lowercase().filter(Char::isLetter)
    return squashed in TEMPLATE
  }
}
