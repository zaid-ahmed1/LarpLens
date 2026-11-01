package com.larplens.guests

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

data class GuestMatch(
    /** The best guest-list name, as written in the list. */
    val name: String,
    /** 0..1 similarity to what was read off the tag. */
    val score: Float,
    /** Next-best candidates (name to score), best first. */
    val runnersUp: List<Pair<String, Float>>,
) {
  /** Another guest scores within [AMBIGUOUS_GAP] of the top one, e.g. a first name shared by several people. */
  val ambiguous: Boolean
    get() = runnersUp.firstOrNull()?.let { score - it.second < AMBIGUOUS_GAP } ?: false

  companion object {
    const val AMBIGUOUS_GAP = 0.02f
  }
}

/**
 * Fuzzy-matches a name read off a name tag against the guest list.
 *
 * Handwriting reads are often partial ("Shayan" for "Shayan Rahimi") or slightly wrong ("Mishe"
 * for "Misha"), so names are compared word by word with Jaro-Winkler similarity, which is forgiving
 * of small typos and rewards a matching start:
 * - one word read: best match against any of the guest's words (their first name counts a bit more)
 * - several words read: each read word is paired with its best guest word, then averaged, blended
 *   with a whole-name comparison
 */
class GuestMatcher(names: List<String>, private val minScore: Float = 0.80f) {
  private class Guest(val name: String, val words: List<String>, val joined: String)

  private val guests =
      names.mapNotNull { name ->
        val words = words(name)
        if (words.isEmpty()) null else Guest(name.trim(), words, words.joinToString(""))
      }

  val size: Int
    get() = guests.size

  /** Best guest for [read], or null if nobody scores at least [minScore]. */
  fun match(read: String, runnersUp: Int = 3): GuestMatch? {
    val readWords = words(read)
    if (readWords.isEmpty()) return null
    val ranked =
        guests
            .map { it to score(readWords, it) }
            .filter { it.second >= minScore }
            .sortedByDescending { it.second }
    val best = ranked.firstOrNull() ?: return null
    return GuestMatch(
        name = best.first.name,
        score = best.second,
        runnersUp = ranked.drop(1).take(runnersUp).map { it.first.name to it.second },
    )
  }

  private fun score(read: List<String>, guest: Guest): Float {
    if (read.size == 1) {
      val word = read[0]
      var best = 0f
      guest.words.forEachIndexed { i, g ->
        // A lone word on a tag is usually a first name
        val weight = if (i == 0) 1f else 0.96f
        best = max(best, jaroWinkler(word, g) * weight)
      }
      return max(best, jaroWinkler(word, guest.joined) * 0.98f)
    }
    val perWord = read.map { r -> guest.words.maxOf { g -> jaroWinkler(r, g) } }.average().toFloat()
    val whole = jaroWinkler(read.joinToString(""), guest.joined)
    // Reading fewer words than the guest has is normal; reading more than they have is suspicious
    val extraWords = (read.size - guest.words.size).coerceAtLeast(0)
    return (0.7f * perWord + 0.3f * whole) - 0.05f * extraWords
  }

  companion object {
    private val NOT_LETTER = Regex("[^\\p{L}\\s'-]")
    private val SPACES = Regex("[\\s'-]+")

    /** Lowercase, accents stripped, split into words. "José O'Neil" -> [jose, o, neil]. */
    fun words(name: String): List<String> {
      val plain =
          Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
      return SPACES.split(NOT_LETTER.replace(plain, " ").trim()).filter { it.isNotEmpty() }
    }

    /** Jaro-Winkler similarity, 0..1. */
    fun jaroWinkler(a: String, b: String): Float {
      if (a == b) return 1f
      if (a.isEmpty() || b.isEmpty()) return 0f
      val window = max(0, max(a.length, b.length) / 2 - 1)
      val aMatched = BooleanArray(a.length)
      val bMatched = BooleanArray(b.length)
      var matches = 0
      for (i in a.indices) {
        val lo = max(0, i - window)
        val hi = min(b.length - 1, i + window)
        for (j in lo..hi) {
          if (!bMatched[j] && a[i] == b[j]) {
            aMatched[i] = true
            bMatched[j] = true
            matches++
            break
          }
        }
      }
      if (matches == 0) return 0f
      var transpositions = 0
      var k = 0
      for (i in a.indices) {
        if (!aMatched[i]) continue
        while (!bMatched[k]) k++
        if (a[i] != b[k]) transpositions++
        k++
      }
      val m = matches.toFloat()
      val jaro = (m / a.length + m / b.length + (m - transpositions / 2f) / m) / 3f
      var prefix = 0
      while (prefix < min(4, min(a.length, b.length)) && a[prefix] == b[prefix]) prefix++
      return jaro + prefix * 0.1f * (1 - jaro)
    }
  }
}
