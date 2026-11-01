package com.larplens.guests

/** One guest: their name and up to three short facts (may be empty). */
data class Guest(val name: String, val facts: List<String>)

/** The bundled guest list (`assets/guests.tsv`, generated at build time from the guest CSV). */
class GuestList(val guests: List<Guest>) {
  private val byName = guests.groupBy { it.name }

  val matcher = GuestMatcher(guests.map { it.name })

  val size: Int
    get() = guests.size

  /** Facts for a matched name. Duplicate names: the first entry that has facts. */
  fun factsFor(name: String): List<String> =
      byName[name].orEmpty().firstOrNull { it.facts.isNotEmpty() }?.facts.orEmpty()

  companion object {
    /** Parses `name<TAB>point1<TAB>point2<TAB>point3` lines. */
    fun parse(tsv: String): GuestList =
        GuestList(
            tsv.lineSequence()
                .mapNotNull { line ->
                  val fields = line.split('\t').map { it.trim() }
                  val name = fields.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                  Guest(name, fields.drop(1).filter { it.isNotEmpty() })
                }
                .toList())

    /** A short pause between the name and each fact (ElevenLabs SSML break tag). */
    const val BREAK_PAUSE = " <break time=\"0.5s\" /> "

    /** For models without break tags: a sentence gap plus an ellipsis reads as a short pause. */
    const val PLAIN_PAUSE = " ... "

    /**
     * What ElevenLabs says: the name, then each fact as its own short sentence, with [pause]
     * between them. "Priya Raman. <pause> CS student at UofT. <pause> Built a robot arm."
     */
    fun spokenBrief(name: String, facts: List<String>, pause: String = BREAK_PAUSE): String {
      val sentences = facts.map(::sentence).filter { it.isNotEmpty() }
      val intro = "${name.trim()}."
      return if (sentences.isEmpty()) "$intro No notes on file."
      else (listOf(intro) + sentences).joinToString(pause)
    }

    /** "cs student at uoft" -> "Cs student at uoft." (capitalized, ends with punctuation). */
    private fun sentence(fact: String): String {
      val text = fact.trim().trimEnd(',', ';')
      if (text.isEmpty()) return ""
      val capitalized = text.replaceFirstChar { it.uppercaseChar() }
      return if (capitalized.last() in ".!?") capitalized else "$capitalized."
    }
  }
}
