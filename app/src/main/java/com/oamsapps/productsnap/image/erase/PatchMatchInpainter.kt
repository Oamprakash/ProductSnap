package com.oamsapps.productsnap.image.erase

import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Exemplar-based hole filling (Wexler et al. / PatchMatch), pure Kotlin.
 *
 * Works coarse-to-fine: the coarsest level is filled by diffusion, then each level
 * alternates PatchMatch (find, for every patch touching the hole, the most similar
 * patch outside it) with voting (each hole pixel becomes a weighted average of what
 * the overlapping matched patches say it should be).
 *
 * Meant for small working images (~400px). [MagicEraser] crops and scales for it.
 */
internal class PatchMatchInpainter(
    private val patchRadius: Int = 3,
    private val minLevelSize: Int = 32,
    private val maxLevels: Int = 6,
    seed: Int = 7,
) {
    private val random = Random(seed)

    private class Level(
        val w: Int,
        val h: Int,
        val r: FloatArray,
        val g: FloatArray,
        val b: FloatArray,
        val hole: BooleanArray,
    )

    /** [argb] is a w×h image; pixels where [hole] is true are replaced. Returns new pixels. */
    suspend fun inpaint(w: Int, h: Int, argb: IntArray, hole: BooleanArray): IntArray {
        val n = w * h
        val base = Level(
            w, h,
            FloatArray(n) { ((argb[it] shr 16) and 0xFF).toFloat() },
            FloatArray(n) { ((argb[it] shr 8) and 0xFF).toFloat() },
            FloatArray(n) { (argb[it] and 0xFF).toFloat() },
            hole.copyOf(),
        )

        val pyramid = mutableListOf(base)
        while (pyramid.size < maxLevels) {
            val last = pyramid.last()
            if (last.w / 2 < minLevelSize || last.h / 2 < minLevelSize) break
            pyramid += downsample(last)
        }

        var prev: Level? = null
        var prevNnf: IntArray? = null
        for (li in pyramid.indices.reversed()) {
            coroutineContext.ensureActive()
            val level = pyramid[li]
            val coarse = prev
            if (coarse == null) diffuseFill(level) else upsampleInto(coarse, level)

            val solver = LevelSolver(level)
            val nnf = solver.initNnf(coarse, prevNnf)
            if (solver.canMatch) {
                val iterations = when {
                    coarse == null -> 8
                    li == 0 -> 3
                    else -> 4
                }
                repeat(iterations) { iter ->
                    coroutineContext.ensureActive()
                    solver.patchMatch(nnf, passes = if (iter == 0) 3 else 2)
                    solver.vote(nnf)
                }
            }
            prev = level
            prevNnf = if (solver.canMatch) nnf else null
        }

        val out = argb.copyOf()
        for (i in 0 until n) if (hole[i]) out[i] = pack(base.r[i], base.g[i], base.b[i])
        return out
    }

    private inner class LevelSolver(private val l: Level) {
        private val radius = patchRadius
        private val w = l.w
        private val h = l.h
        private val validSource = BooleanArray(w * h)
        private val validList: IntArray
        private val targets: IntArray
        private val holeIndices: IntArray
        private val dist = FloatArray(w * h)
        val canMatch: Boolean

        init {
            // Prefix sums of hole flags → O(1) "does this box touch the hole?" queries.
            val stride = w + 1
            val ps = IntArray(stride * (h + 1))
            for (y in 0 until h) {
                var row = 0
                for (x in 0 until w) {
                    if (l.hole[y * w + x]) row++
                    ps[(y + 1) * stride + x + 1] = ps[y * stride + x + 1] + row
                }
            }
            fun holesIn(x0: Int, y0: Int, x1: Int, y1: Int): Int {
                val a = max(0, x0); val b = max(0, y0)
                val c = min(w - 1, x1); val d = min(h - 1, y1)
                if (a > c || b > d) return 0
                return ps[(d + 1) * stride + c + 1] - ps[b * stride + c + 1] - ps[(d + 1) * stride + a] + ps[b * stride + a]
            }

            val valid = IntList()
            for (y in radius until h - radius) for (x in radius until w - radius) {
                if (holesIn(x - radius, y - radius, x + radius, y + radius) == 0) {
                    val i = y * w + x
                    validSource[i] = true
                    valid.add(i)
                }
            }
            validList = valid.toArray()

            val t = IntList()
            val hs = IntList()
            for (y in 0 until h) for (x in 0 until w) {
                if (holesIn(x - radius, y - radius, x + radius, y + radius) > 0) t.add(y * w + x)
                if (l.hole[y * w + x]) hs.add(y * w + x)
            }
            targets = t.toArray()
            holeIndices = hs.toArray()
            canMatch = validList.isNotEmpty() && targets.isNotEmpty()
        }

        fun initNnf(coarse: Level?, coarseNnf: IntArray?): IntArray {
            val nnf = IntArray(w * h) { -1 }
            if (!canMatch) return nnf
            for (t in targets) {
                var candidate = -1
                if (coarse != null && coarseNnf != null) {
                    val x = t % w; val y = t / w
                    val cx = min(x / 2, coarse.w - 1)
                    val cy = min(y / 2, coarse.h - 1)
                    val cs = coarseNnf[cy * coarse.w + cx]
                    if (cs >= 0) {
                        val sx = (cs % coarse.w) * 2 + (x and 1)
                        val sy = (cs / coarse.w) * 2 + (y and 1)
                        if (sx in 0 until w && sy in 0 until h && validSource[sy * w + sx]) candidate = sy * w + sx
                    }
                }
                if (candidate < 0) candidate = validList[random.nextInt(validList.size)]
                nnf[t] = candidate
            }
            return nnf
        }

        fun patchMatch(nnf: IntArray, passes: Int) {
            // Hole pixels changed in the last vote, so stored distances are stale.
            for (t in targets) dist[t] = distance(t, nnf[t], Float.MAX_VALUE)

            for (p in 0 until passes) {
                val step = if (p % 2 == 0) 1 else -1
                var k = if (step == 1) 0 else targets.size - 1
                while (k in targets.indices) {
                    val t = targets[k]
                    val tx = t % w; val ty = t / w

                    // Propagation: a neighbour's good match, shifted by one, is often good here too.
                    propagate(nnf, t, tx - step, ty, step, 0)
                    propagate(nnf, t, tx, ty - step, 0, step)

                    // Random search in shrinking windows around the current best.
                    val best = nnf[t]
                    val bx = best % w; val by = best / w
                    var r = max(w, h)
                    while (r >= 1) {
                        val cx = bx + random.nextInt(-r, r + 1)
                        val cy = by + random.nextInt(-r, r + 1)
                        if (cx in 0 until w && cy in 0 until h) {
                            val c = cy * w + cx
                            if (validSource[c]) improve(nnf, t, c)
                        }
                        r /= 2
                    }
                    k += step
                }
            }
        }

        private fun propagate(nnf: IntArray, t: Int, nx: Int, ny: Int, ox: Int, oy: Int) {
            if (nx !in 0 until w || ny !in 0 until h) return
            val sn = nnf[ny * w + nx]
            if (sn < 0) return
            val sx = sn % w + ox
            val sy = sn / w + oy
            if (sx !in 0 until w || sy !in 0 until h) return
            val c = sy * w + sx
            if (validSource[c]) improve(nnf, t, c)
        }

        private fun improve(nnf: IntArray, t: Int, c: Int) {
            if (c == nnf[t]) return
            val d = distance(t, c, dist[t])
            if (d < dist[t]) {
                nnf[t] = c
                dist[t] = d
            }
        }

        /** Sum of squared RGB differences; stops early once past [limit]. */
        private fun distance(t: Int, s: Int, limit: Float): Float {
            val tx = t % w; val ty = t / w
            val sx = s % w; val sy = s / w
            var d = 0f
            for (dy in -radius..radius) {
                val tRow = (ty + dy).coerceIn(0, h - 1) * w
                val sRow = (sy + dy) * w
                for (dx in -radius..radius) {
                    val ti = tRow + (tx + dx).coerceIn(0, w - 1)
                    val si = sRow + sx + dx
                    val dr = l.r[ti] - l.r[si]
                    val dg = l.g[ti] - l.g[si]
                    val db = l.b[ti] - l.b[si]
                    d += dr * dr + dg * dg + db * db
                }
                if (d > limit) return d
            }
            return d
        }

        fun vote(nnf: IntArray) {
            val ds = FloatArray(targets.size) { dist[targets[it]] }
            ds.sort()
            val sigma2 = max(ds[min(ds.size - 1, ds.size * 3 / 4)], 1f)

            val nr = FloatArray(holeIndices.size)
            val ng = FloatArray(holeIndices.size)
            val nb = FloatArray(holeIndices.size)
            for ((k, q) in holeIndices.withIndex()) {
                val qx = q % w; val qy = q / w
                var sr = 0f; var sg = 0f; var sb = 0f; var sw = 0f
                for (dy in -radius..radius) {
                    val ty = qy + dy
                    if (ty !in 0 until h) continue
                    for (dx in -radius..radius) {
                        val tx = qx + dx
                        if (tx !in 0 until w) continue
                        val t = ty * w + tx
                        val s = nnf[t]
                        if (s < 0) continue
                        // q sits at offset (-dx, -dy) in t's patch, so read the same offset in the match.
                        val si = (s / w - dy) * w + (s % w - dx)
                        val weight = max(exp(-dist[t] / (2f * sigma2)), 1e-6f)
                        sr += weight * l.r[si]
                        sg += weight * l.g[si]
                        sb += weight * l.b[si]
                        sw += weight
                    }
                }
                if (sw > 0f) {
                    nr[k] = sr / sw; ng[k] = sg / sw; nb[k] = sb / sw
                } else {
                    nr[k] = l.r[q]; ng[k] = l.g[q]; nb[k] = l.b[q]
                }
            }
            for ((k, q) in holeIndices.withIndex()) {
                l.r[q] = nr[k]; l.g[q] = ng[k]; l.b[q] = nb[k]
            }
        }
    }

    private fun downsample(l: Level): Level {
        val w2 = l.w / 2
        val h2 = l.h / 2
        val n2 = w2 * h2
        val r = FloatArray(n2); val g = FloatArray(n2); val b = FloatArray(n2)
        val hole = BooleanArray(n2)
        for (y in 0 until h2) for (x in 0 until w2) {
            var cr = 0f; var cg = 0f; var cb = 0f; var cnt = 0
            var anyHole = false
            for (j in 0..1) for (i in 0..1) {
                val idx = (2 * y + j) * l.w + (2 * x + i)
                if (l.hole[idx]) anyHole = true
                else { cr += l.r[idx]; cg += l.g[idx]; cb += l.b[idx]; cnt++ }
            }
            val o = y * w2 + x
            hole[o] = anyHole
            if (cnt > 0) { r[o] = cr / cnt; g[o] = cg / cnt; b[o] = cb / cnt }
        }
        return Level(w2, h2, r, g, b, hole)
    }

    /** Seeds the fine level's hole with the coarse solution (bilinear). */
    private fun upsampleInto(coarse: Level, fine: Level) {
        for (y in 0 until fine.h) for (x in 0 until fine.w) {
            val i = y * fine.w + x
            if (!fine.hole[i]) continue
            val fx = ((x + 0.5f) / 2f - 0.5f).coerceIn(0f, (coarse.w - 1).toFloat())
            val fy = ((y + 0.5f) / 2f - 0.5f).coerceIn(0f, (coarse.h - 1).toFloat())
            val x0 = fx.toInt(); val y0 = fy.toInt()
            val x1 = min(x0 + 1, coarse.w - 1); val y1 = min(y0 + 1, coarse.h - 1)
            val ax = fx - x0; val ay = fy - y0
            fun bilerp(c: FloatArray): Float {
                val top = c[y0 * coarse.w + x0] * (1 - ax) + c[y0 * coarse.w + x1] * ax
                val bottom = c[y1 * coarse.w + x0] * (1 - ax) + c[y1 * coarse.w + x1] * ax
                return top * (1 - ay) + bottom * ay
            }
            fine.r[i] = bilerp(coarse.r)
            fine.g[i] = bilerp(coarse.g)
            fine.b[i] = bilerp(coarse.b)
        }
    }

    /** Onion-peel fill: repeatedly set each hole pixel on the edge to the mean of its known neighbours. */
    private fun diffuseFill(l: Level) {
        val n = l.w * l.h
        val known = BooleanArray(n) { !l.hole[it] }
        if (known.none { it }) {
            for (i in 0 until n) { l.r[i] = 128f; l.g[i] = 128f; l.b[i] = 128f }
            return
        }
        var remaining = l.hole.count { it }
        while (remaining > 0) {
            val idx = IntList()
            val vr = ArrayList<Float>(); val vg = ArrayList<Float>(); val vb = ArrayList<Float>()
            for (y in 0 until l.h) for (x in 0 until l.w) {
                val i = y * l.w + x
                if (known[i]) continue
                var cr = 0f; var cg = 0f; var cb = 0f; var cnt = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until l.w || ny !in 0 until l.h) continue
                    val j = ny * l.w + nx
                    if (known[j]) { cr += l.r[j]; cg += l.g[j]; cb += l.b[j]; cnt++ }
                }
                if (cnt > 0) { idx.add(i); vr += cr / cnt; vg += cg / cnt; vb += cb / cnt }
            }
            if (idx.size == 0) break
            for (k in 0 until idx.size) {
                val i = idx[k]
                l.r[i] = vr[k]; l.g[i] = vg[k]; l.b[i] = vb[k]
                known[i] = true
            }
            remaining -= idx.size
        }
    }

    private fun pack(r: Float, g: Float, b: Float): Int =
        (0xFF shl 24) or
            (r.toInt().coerceIn(0, 255) shl 16) or
            (g.toInt().coerceIn(0, 255) shl 8) or
            b.toInt().coerceIn(0, 255)

    /** Growable int list without boxing. */
    private class IntList {
        private var data = IntArray(64)
        var size = 0
            private set

        fun add(v: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }

        operator fun get(i: Int) = data[i]
        fun isNotEmpty() = size > 0
        fun toArray(): IntArray = data.copyOf(size)
    }
}
