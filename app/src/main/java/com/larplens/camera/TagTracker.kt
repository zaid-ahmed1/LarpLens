package com.larplens.camera

/**
 * Debounces per-frame detections into a stable "locked on a tag" signal, so one lucky frame doesn't
 * chime and one dropped frame doesn't unlock.
 *
 * Locks when [needed] of the last [window] analyzed frames saw a tag in roughly the same place;
 * unlocks after [lostAfter] consecutive misses.
 */
class TagTracker(
    private val window: Int = 4,
    private val needed: Int = 3,
    private val lostAfter: Int = 3,
    private val minIou: Float = 0.3f,
) {
  enum class Event { ACQUIRED, LOST }

  private val history = ArrayDeque<TagDetection?>()

  var locked: Boolean = false
    private set

  fun update(detection: TagDetection?): Event? {
    history.addLast(detection)
    while (history.size > maxOf(window, lostAfter)) history.removeFirst()

    if (!locked) {
      val reference = detection ?: return null
      val agreeing =
          history.takeLast(window).count { it != null && it.box.iou(reference.box) >= minIou }
      if (agreeing >= needed) {
        locked = true
        return Event.ACQUIRED
      }
    } else if (history.size >= lostAfter && history.takeLast(lostAfter).all { it == null }) {
      locked = false
      return Event.LOST
    }
    return null
  }

  fun reset() {
    history.clear()
    locked = false
  }
}
