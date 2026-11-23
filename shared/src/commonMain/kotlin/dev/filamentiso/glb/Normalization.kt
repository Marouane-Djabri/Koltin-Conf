package dev.filamentiso.glb

import dev.filamentiso.Vec3
import kotlin.math.max

/**
 * How to place an imported model without touching the file: move its pivot (centre of the
 * footprint, at the lowest point) to the origin, then scale so the largest horizontal side
 * is [targetFootprint] metres.
 *
 * Renderers apply: world = R(y) * S(fitScale * userScale) * (p - pivot) + offset
 */
data class Normalization(val pivot: Vec3, val fitScale: Float) {
    /** Size in metres after fitting (before the user's own scale). */
    fun fittedSize(bboxMin: Vec3, bboxMax: Vec3) = (bboxMax - bboxMin) * fitScale

    companion object {
        const val HOME_FOOTPRINT_M = 20f
        private const val MIN_SIDE = 1e-4f

        fun of(bboxMin: Vec3, bboxMax: Vec3, targetFootprint: Float): Normalization {
            val pivot = Vec3((bboxMin.x + bboxMax.x) / 2f, bboxMin.y, (bboxMin.z + bboxMax.z) / 2f)
            val side = max(bboxMax.x - bboxMin.x, bboxMax.z - bboxMin.z)
            // A flat or degenerate footprint (e.g. a single vertical plane) keeps its size.
            val scale = if (side < MIN_SIDE) 1f else targetFootprint / side
            return Normalization(pivot, scale)
        }

        /** Fit a model into a w x d footprint (furniture): the smaller of both ratios, so it never overflows. */
        fun fitInto(bboxMin: Vec3, bboxMax: Vec3, width: Float, depth: Float): Normalization {
            val pivot = Vec3((bboxMin.x + bboxMax.x) / 2f, bboxMin.y, (bboxMin.z + bboxMax.z) / 2f)
            val sx = bboxMax.x - bboxMin.x
            val sz = bboxMax.z - bboxMin.z
            val scale = when {
                sx < MIN_SIDE && sz < MIN_SIDE -> 1f
                sx < MIN_SIDE -> depth / sz
                sz < MIN_SIDE -> width / sx
                else -> minOf(width / sx, depth / sz)
            }
            return Normalization(pivot, scale)
        }
    }
}
