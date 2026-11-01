package com.larplens.ocr

/**
 * OCR on handwriting flickers between frames ("ZAID", "ZAlD", "ZAID"). While a tag stays locked,
 * collect readings and confirm the name once the same one has come back [needed] times.
 */
class ReadingVoter(private val needed: Int = 2) {
  private val votes = linkedMapOf<String, Pair<String, Int>>() // key -> (display form, count)

  /** Adds a reading; returns the confirmed name the first time one reaches [needed] votes. */
  fun add(name: String?): String? {
    if (name == null) return null
    val key = NameExtractor.normalize(name)
    if (key.isEmpty()) return null
    val (_, count) = votes[key] ?: (name to 0)
    votes[key] = name to count + 1
    return if (count + 1 == needed) name else null
  }

  /** Current front-runner and its vote count, for the debug view. */
  fun leader(): Pair<String, Int>? = votes.values.maxByOrNull { it.second }

  fun reset() = votes.clear()
}
