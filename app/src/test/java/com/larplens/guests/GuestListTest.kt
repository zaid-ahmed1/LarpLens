package com.larplens.guests

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** All names and facts here are made up. */
class GuestListTest {
  private val tsv =
      listOf(
              "Priya Raman\tcs student at uoft\tbuilt a robot arm\tlikes cats",
              "Sam Okafor\tdesigner, developer, and educator\t\t",
              "Mei Chen\t\t\t",
              "\t\t\t", // blank name: skipped
              "Sam Okafor\t\t\t", // duplicate name without facts
          )
          .joinToString("\n")

  private val list = GuestList.parse(tsv)

  @Test
  fun parsesNamesAndFacts() {
    assertEquals(4, list.size)
    assertEquals(listOf("cs student at uoft", "built a robot arm", "likes cats"), list.factsFor("Priya Raman"))
    assertEquals(emptyList<String>(), list.factsFor("Mei Chen"))
  }

  @Test
  fun duplicateNamesUseTheEntryWithFacts() {
    assertEquals(listOf("designer, developer, and educator"), list.factsFor("Sam Okafor"))
  }

  @Test
  fun matcherCoversTheList() {
    assertEquals("Priya Raman", list.matcher.match("priya")!!.name)
  }

  @Test
  fun spokenBriefReadsNameThenFacts() {
    assertEquals(
        "Priya Raman. Cs student at uoft. Built a robot arm. Likes cats.",
        GuestList.spokenBrief("Priya Raman", list.factsFor("Priya Raman"), pause = " "),
    )
  }

  @Test
  fun spokenBriefPausesBetweenNameAndEachFact() {
    val brief = GuestList.spokenBrief("Priya Raman", list.factsFor("Priya Raman"))
    assertEquals(
        "Priya Raman. <break time=\"0.5s\" /> Cs student at uoft. <break time=\"0.5s\" /> " +
            "Built a robot arm. <break time=\"0.5s\" /> Likes cats.",
        brief,
    )
    // Three pauses at most: ElevenLabs warns many break tags in one request can destabilize audio
    assertEquals(3, Regex("<break").findAll(brief).count())
  }

  @Test
  fun spokenBriefKeepsExistingPunctuationAndTrimsTrailingCommas() {
    assertEquals(
        "Sam. Won first place! Ships fast.",
        GuestList.spokenBrief("Sam", listOf("won first place!", "ships fast,"), pause = " "),
    )
  }

  @Test
  fun spokenBriefWithoutFacts() {
    val brief = GuestList.spokenBrief("Mei Chen", emptyList())
    assertTrue(brief.startsWith("Mei Chen."))
    assertTrue(brief.contains("No notes"))
  }
}
