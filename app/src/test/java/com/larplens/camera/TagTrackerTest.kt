package com.larplens.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagTrackerTest {
  private fun det(left: Float = 0.3f, top: Float = 0.4f) =
      TagDetection(NormRect(left, top, left + 0.3f, top + 0.15f), score = 1f, fill = 0.9f, ink = 0.1f)

  @Test
  fun locksAfterThreeAgreeingFrames() {
    val t = TagTracker()
    assertNull(t.update(det()))
    assertNull(t.update(det()))
    assertEquals(TagTracker.Event.ACQUIRED, t.update(det()))
    assertTrue(t.locked)
    assertNull("no repeat chime while locked", t.update(det()))
  }

  @Test
  fun toleratesOneDroppedFrame() {
    val t = TagTracker()
    t.update(det())
    t.update(null)
    t.update(det())
    assertEquals(TagTracker.Event.ACQUIRED, t.update(det()))
  }

  @Test
  fun jumpingDetectionsDoNotLock() {
    val t = TagTracker()
    t.update(det(left = 0.0f, top = 0.0f))
    t.update(det(left = 0.6f, top = 0.1f))
    t.update(det(left = 0.1f, top = 0.8f))
    assertFalse(t.locked)
  }

  @Test
  fun unlocksAfterThreeMisses() {
    val t = TagTracker()
    repeat(3) { t.update(det()) }
    assertNull(t.update(null))
    assertNull(t.update(null))
    assertEquals(TagTracker.Event.LOST, t.update(null))
    assertFalse(t.locked)
  }
}
