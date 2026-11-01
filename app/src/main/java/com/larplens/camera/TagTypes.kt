package com.larplens.camera

import kotlin.math.max
import kotlin.math.min

/** Rectangle in normalized frame coordinates (0..1), so it survives resolution changes. */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
  val width: Float
    get() = right - left

  val height: Float
    get() = bottom - top

  fun iou(other: NormRect): Float {
    val ix = max(0f, min(right, other.right) - max(left, other.left))
    val iy = max(0f, min(bottom, other.bottom) - max(top, other.top))
    val inter = ix * iy
    val union = width * height + other.width * other.height - inter
    return if (union <= 0f) 0f else inter / union
  }
}

data class TagDetection(
    /** Axis-aligned bounds, normalized. Used for tracking, cropping and the overlay. */
    val box: NormRect,
    /** Higher is better; combines rectangularity, size, and how central it is. */
    val score: Float,
    /** Share of the tag's rotated bounding rectangle the white shape fills (1.0 = perfect). */
    val fill: Float,
    /** Share of the tag covered by dark marks (handwriting). */
    val ink: Float,
)

/** Finds a name tag sticker in a raw I420 camera frame. */
interface TagDetector {
  fun detect(yuv: ByteArray, width: Int, height: Int): TagDetection?
}
