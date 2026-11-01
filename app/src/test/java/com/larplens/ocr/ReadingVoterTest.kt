package com.larplens.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingVoterTest {
  @Test
  fun confirmsOnSecondMatchingReadOnlyOnce() {
    val v = ReadingVoter()
    assertNull(v.add("ZAID"))
    assertNull(v.add("ZAlD"))
    assertEquals("zaid", v.add("zaid")?.lowercase())
    assertNull("already confirmed", v.add("Zaid"))
    assertEquals(3, v.leader()?.second)
  }

  @Test
  fun ignoresEmptyReads() {
    val v = ReadingVoter()
    assertNull(v.add(null))
    assertNull(v.add("!!"))
    assertNull(v.leader())
  }

  @Test
  fun resetStartsOver() {
    val v = ReadingVoter()
    v.add("Mei")
    v.reset()
    assertNull(v.add("Mei"))
  }
}
