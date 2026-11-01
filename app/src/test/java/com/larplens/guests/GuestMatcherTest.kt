package com.larplens.guests

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** All names here are made up. */
class GuestMatcherTest {
  private val matcher =
      GuestMatcher(
          listOf(
              "Priya Raman",
              "Sam Okafor",
              "Mei Chen",
              "Misha Volkov",
              "Jean-Luc O'Neil",
              "José Alvarez",
              "Tariq Haddad",
              "Tariq Bello",
              "Nadia Kowalski",
              "Oluwaseun Adeyemi",
              "lowercase person",
              "Ravi",
          ))

  @Test
  fun exactFullName() {
    val m = matcher.match("Sam Okafor")!!
    assertEquals("Sam Okafor", m.name)
    assertEquals(1f, m.score, 0.001f)
    assertFalse(m.ambiguous)
  }

  @Test
  fun firstNameOnlyFindsTheGuest() {
    assertEquals("Priya Raman", matcher.match("PRIYA")!!.name)
    assertEquals("Ravi", matcher.match("ravi")!!.name)
  }

  @Test
  fun handwritingTyposStillMatch() {
    assertEquals("Misha Volkov", matcher.match("Mishe")!!.name)
    assertEquals("Nadia Kowalski", matcher.match("Nadja Kowalsky")!!.name)
    assertEquals("Oluwaseun Adeyemi", matcher.match("Oluwasun")!!.name)
  }

  @Test
  fun accentsHyphensAndApostrophesAreIgnored() {
    assertEquals("José Alvarez", matcher.match("Jose")!!.name)
    assertEquals("Jean-Luc O'Neil", matcher.match("Jean Luc ONeil")!!.name)
  }

  @Test
  fun sharedFirstNameIsFlaggedAmbiguous() {
    val m = matcher.match("Tariq")!!
    assertTrue(m.name.startsWith("Tariq"))
    assertTrue("two Tariqs: should be ambiguous", m.ambiguous)
    assertTrue(m.runnersUp.first().first.startsWith("Tariq"))
  }

  @Test
  fun fullNameBreaksTheTie() {
    val m = matcher.match("Tariq Bello")!!
    assertEquals("Tariq Bello", m.name)
    assertFalse(m.ambiguous)
  }

  @Test
  fun nonGuestReturnsNothing() {
    assertNull(matcher.match("Bartholomew"))
    assertNull(matcher.match("Zz"))
    assertNull(matcher.match("!!"))
  }

  @Test
  fun caseInsensitive() {
    assertEquals("lowercase person", matcher.match("LOWERCASE PERSON")!!.name)
  }
}
