package com.oamsapps.imaging

import kotlin.math.max
import kotlin.math.min

/**
 * Edge refinement for ML Kit masks, in two parts. Pure Kotlin on plain arrays, so it's unit-tested on the JVM
 * like [MaskOps].
 *
 * Why: ML Kit's confidence mask is blurry at the outline and sits a few pixels off the photo's real edges, so fuzz,
 * lace and fur get chopped or keep a fringe of the old background.
 *
 *  1. [refine] (alpha): a trimap marks a thin band around the outline; only that band is re-solved, so the
 *     interior and the clear background are never touched. In the band, each pixel's alpha is first estimated from
 *     colour (how far it sits between the local background and local item colours), falling back to ML Kit's value
 *     where the two colours are too alike to tell apart. A colour guided filter (He et al., "Guided Image
 *     Filtering", 2010) then smooths that estimate without pulling it off the photo's edges.
 *  2. [decontaminate] (colour): semi-transparent edge pixels still carry the old background's tint, which shows as
 *     a halo on white. Each is unmixed back to the item's own colour.
 */
object Matting {

    /** Sizes are in pixels of the image the mask was segmented at. */
    class Params(
        /** Half-width of the band around the outline that gets re-solved. Outside it the mask is kept as is. */
        val bandPx: Int,
        /** Guided filter window radius. */
        val radius: Int,
        /** Guided filter regularisation (colours in 0..1). Lower = edges follow the photo more tightly, but noisier. */
        val eps: Float,
        /** Window for the local item / background colours (used by [refine] and [edgeColors]). */
        val colorRadius: Int,
    )

    /** Starting values for a mask segmented at [segLongEdge] (1024 in the pipeline: band 6, radius 6). */
    fun params(segLongEdge: Int): Params {
        val unit = max(2, Math.round(segLongEdge / 170f))
        return Params(bandPx = unit, radius = unit, eps = 1e-3f, colorRadius = 2 * unit)
    }

    /**
     * Refines [mask] using [rgb], the photo the mask was segmented from (ARGB ints, same size as the mask).
     * Returns a new mask; values outside the band are exactly 0 or 1.
     */
    fun refine(mask: Mask, rgb: IntArray, p: Params): Mask {
        val w = mask.width
        val h = mask.height
        require(rgb.size == w * h) { "Image has ${rgb.size} pixels, mask ${w * h}" }
        val tri = trimap(mask, p.bandPx)
        val out = FloatArray(w * h) { if (tri[it] == FG) 1f else 0f }

        // Work only on the band's bounding box (plus the filter's reach): less memory and time on small items.
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) for (x in 0 until w) if (tri[y * w + x] == UNKNOWN) {
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        if (x1 < 0) return Mask(out, w, h) // no outline at all (empty or full mask)
        val pad = 2 * p.radius + 1
        x0 = max(0, x0 - pad); y0 = max(0, y0 - pad); x1 = min(w - 1, x1 + pad); y1 = min(h - 1, y1 + pad)
        val cw = x1 - x0 + 1
        val ch = y1 - y0 + 1

        // 1) A first guess for each unknown pixel from colour: where along the line from the local background
        //    colour to the local item colour does it sit? This is what moves the edge onto the photo's real edge and
        //    keeps fibres the mask missed. Where item and background colours are too close to tell apart, the guess
        //    falls back to ML Kit's own confidence.
        val cropTri = ByteArray(cw * ch) { tri[(it / cw + y0) * w + it % cw + x0] }
        val cropRgb = IntArray(cw * ch) { rgb[(it / cw + y0) * w + it % cw + x0] }
        val local = localColors(cropTri, cropRgb, cw, ch, p.colorRadius)
        val r = FloatArray(cw * ch); val g = FloatArray(cw * ch); val b = FloatArray(cw * ch); val m = FloatArray(cw * ch)
        val trustAt = FloatArray(cw * ch)
        for (y in 0 until ch) for (x in 0 until cw) {
            val src = (y + y0) * w + (x + x0)
            val c = rgb[src]
            val i = y * cw + x
            val cr = ((c shr 16) and 0xFF).toFloat(); val cg = ((c shr 8) and 0xFF).toFloat(); val cb = (c and 0xFF).toFloat()
            r[i] = cr / 255f; g[i] = cg / 255f; b[i] = cb / 255f
            m[i] = when (tri[src]) {
                FG -> 1f
                BG -> 0f
                else -> {
                    val ml = mask.values[src].coerceIn(0f, 1f)
                    val k = 3 * i
                    val fr = local.fg[k]; val br = local.bg[k]
                    if (fr.isNaN() || br.isNaN()) ml else {
                        val dr = fr - br; val dg = local.fg[k + 1] - local.bg[k + 1]; val db = local.fg[k + 2] - local.bg[k + 2]
                        val d2 = dr * dr + dg * dg + db * db
                        val byColour = (((cr - br) * dr + (cg - local.bg[k + 1]) * dg + (cb - local.bg[k + 2]) * db) / max(d2, 1f))
                            .coerceIn(0f, 1f)
                        // Trust colour fully once item and background differ by ~60 (of 255) or more.
                        val trust = ((Math.sqrt(d2.toDouble()).toFloat() - MIN_CONTRAST) / (FULL_CONTRAST - MIN_CONTRAST))
                            .coerceIn(0f, 1f)
                        trustAt[i] = trust
                        trust * byColour + (1f - trust) * ml
                    }
                }
            }
        }
        // 2) Guided filter: smooths that guess without moving it off the photo's edges (removes speckle from noise
        //    and texture). Where colour wasn't trusted the guess is ML Kit's mask, already smooth; the filter would
        //    only blur it there, so its answer is blended in by the same trust. Only the band is written.
        val q = guidedFilter(r, g, b, m, cw, ch, p.radius, p.eps)

        for (y in 0 until ch) for (x in 0 until cw) {
            val dst = (y + y0) * w + (x + x0)
            if (tri[dst] != UNKNOWN) continue
            val i = y * cw + x
            var v = trustAt[i] * q[i] + (1f - trustAt[i]) * m[i]
            v = if (v < SNAP) 0f else if (v > 1f - SNAP) 1f else v
            out[dst] = v
        }
        return Mask(out, w, h)
    }

    internal const val BG: Byte = 0
    internal const val FG: Byte = 1
    internal const val UNKNOWN: Byte = 2
    /** Alpha this close to 0 or 1 is snapped, so the filter doesn't leave a faint haze or pinholes. */
    private const val SNAP = 0.03f
    /** Colour distance (0..255 RGB) below which item and background can't be told apart by colour at all. */
    private const val MIN_CONTRAST = 20f
    private const val FULL_CONTRAST = 60f

    /** Sure item = mask eroded by [band]; sure background = background eroded by [band]; the rest is unknown. */
    internal fun trimap(mask: Mask, band: Int): ByteArray {
        val w = mask.width
        val h = mask.height
        val fg = FloatArray(w * h) { if (mask.values[it] >= 0.5f) 1f else 0f }
        val bg = FloatArray(w * h) { 1f - fg[it] }
        val sureFg = MaskOps.minFilter(fg, w, h, band)
        val sureBg = MaskOps.minFilter(bg, w, h, band)
        return ByteArray(w * h) { i -> if (sureFg[i] == 1f) FG else if (sureBg[i] == 1f) BG else UNKNOWN }
    }

    /** Guided filter with a colour guide (r, g, b in 0..1) on input [p]. Returns the filtered values. */
    internal fun guidedFilter(
        r: FloatArray, g: FloatArray, b: FloatArray, p: FloatArray, w: Int, h: Int, rad: Int, eps: Float,
    ): FloatArray {
        val n = w * h
        fun box(a: FloatArray) = MaskOps.boxBlur(a, w, h, rad)
        fun prod(a: FloatArray, c: FloatArray) = FloatArray(n) { a[it] * c[it] }

        val mr = box(r); val mg = box(g); val mb = box(b); val mp = box(p)
        val ar = FloatArray(n); val ag = FloatArray(n); val ab = FloatArray(n); val bb = FloatArray(n)
        run {
            val cpr = box(prod(r, p)); val cpg = box(prod(g, p)); val cpb = box(prod(b, p))
            val vrr = box(prod(r, r)); val vrg = box(prod(r, g)); val vrb = box(prod(r, b))
            val vgg = box(prod(g, g)); val vgb = box(prod(g, b)); val vbb = box(prod(b, b))
            for (i in 0 until n) {
                // Covariance of guide and input, and the guide's 3x3 covariance (+ eps on the diagonal).
                val cr = cpr[i] - mr[i] * mp[i]; val cg = cpg[i] - mg[i] * mp[i]; val cb = cpb[i] - mb[i] * mp[i]
                val s11 = vrr[i] - mr[i] * mr[i] + eps; val s12 = vrg[i] - mr[i] * mg[i]; val s13 = vrb[i] - mr[i] * mb[i]
                val s22 = vgg[i] - mg[i] * mg[i] + eps; val s23 = vgb[i] - mg[i] * mb[i]; val s33 = vbb[i] - mb[i] * mb[i] + eps
                // Inverse of the symmetric matrix by cofactors (always invertible: eps > 0 keeps it positive definite).
                val i11 = s22 * s33 - s23 * s23; val i12 = s13 * s23 - s12 * s33; val i13 = s12 * s23 - s13 * s22
                val i22 = s11 * s33 - s13 * s13; val i23 = s13 * s12 - s11 * s23; val i33 = s11 * s22 - s12 * s12
                val det = s11 * i11 + s12 * i12 + s13 * i13
                val a1 = (i11 * cr + i12 * cg + i13 * cb) / det
                val a2 = (i12 * cr + i22 * cg + i23 * cb) / det
                val a3 = (i13 * cr + i23 * cg + i33 * cb) / det
                ar[i] = a1; ag[i] = a2; ab[i] = a3
                bb[i] = mp[i] - a1 * mr[i] - a2 * mg[i] - a3 * mb[i]
            }
        }
        val mar = box(ar); val mag = box(ag); val mab = box(ab); val mbb = box(bb)
        return FloatArray(n) { mar[it] * r[it] + mag[it] * g[it] + mab[it] * b[it] + mbb[it] }
    }

    /**
     * Local item and background colours near the outline, at mask size: the average colour of nearby pixels that are
     * clearly item / clearly background. Built once from the segmentation-size image, then used at full size.
     */
    class EdgeColors internal constructor(
        /** Size of the mask these were made from (the cutout is sampled against this). */
        val width: Int,
        val height: Int,
        /** The region the colours cover, in mask pixels: only around the semi-transparent edge. */
        internal val x0: Int,
        internal val y0: Int,
        internal val cw: Int,
        internal val ch: Int,
        /** RGB in 0..255 per region pixel (3 floats); NaN where no clear pixel was near. */
        internal val fg: FloatArray,
        internal val bg: FloatArray,
    )

    /** Local colours for [decontaminate], from a finished mask. Computed only around its soft edge. */
    fun edgeColors(mask: Mask, rgb: IntArray, radius: Int): EdgeColors {
        val w = mask.width
        val h = mask.height
        fun cls(v: Float) = if (v > 0.97f) FG else if (v < 0.03f) BG else UNKNOWN
        // The edge: soft pixels, and pixels where the mask flips between item and background (a crisp mask still
        // gets soft edge pixels when it's scaled up to the cutout's size).
        val v = mask.values
        fun isEdge(x: Int, y: Int): Boolean {
            val c = v[y * w + x]
            if (cls(c) == UNKNOWN) return true
            val fg = c >= 0.5f
            return (x + 1 < w && (v[y * w + x + 1] >= 0.5f) != fg) || (y + 1 < h && (v[(y + 1) * w + x] >= 0.5f) != fg)
        }
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) for (x in 0 until w) if (isEdge(x, y)) {
            if (x < x0) x0 = x; if (x + 1 > x1) x1 = x + 1; if (y < y0) y0 = y; if (y + 1 > y1) y1 = y + 1
        }
        if (x1 < 0) return EdgeColors(w, h, 0, 0, 0, 0, FloatArray(0), FloatArray(0))
        x0 = max(0, x0 - radius - 1); y0 = max(0, y0 - radius - 1); x1 = min(w - 1, x1 + radius + 1); y1 = min(h - 1, y1 + radius + 1)
        val cw = x1 - x0 + 1
        val ch = y1 - y0 + 1
        val cropCls = ByteArray(cw * ch) { cls(mask.values[(it / cw + y0) * w + it % cw + x0]) }
        val cropRgb = IntArray(cw * ch) { rgb[(it / cw + y0) * w + it % cw + x0] }
        val local = localColors(cropCls, cropRgb, cw, ch, radius)
        return EdgeColors(w, h, x0, y0, cw, ch, local.fg, local.bg)
    }

    private class Local(val fg: FloatArray, val bg: FloatArray)

    /** Average colour of nearby [FG] pixels and of nearby [BG] pixels in [cls], per pixel. */
    private fun localColors(cls: ByteArray, rgb: IntArray, w: Int, h: Int, radius: Int): Local {
        fun localMean(want: Byte): FloatArray {
            val wt = FloatArray(w * h) { if (cls[it] == want) 1f else 0f }
            val sw = MaskOps.boxBlur(wt, w, h, radius)
            val out = FloatArray(3 * w * h)
            for ((k, shift) in intArrayOf(16, 8, 0).withIndex()) {
                val s = MaskOps.boxBlur(FloatArray(w * h) { wt[it] * ((rgb[it] shr shift) and 0xFF) }, w, h, radius)
                for (i in 0 until w * h) out[3 * i + k] = if (sw[i] > 1e-4f) s[i] / sw[i] else Float.NaN
            }
            return out
        }
        return Local(localMean(FG), localMean(BG))
    }

    /**
     * Removes the old background's tint from semi-transparent pixels of a cutout, in place. [px] is the cutout as
     * unpremultiplied ARGB ints (what Bitmap.getPixels returns), any size; [colors] is at mask size and is sampled
     * to match. A pixel showing c = a*F + (1-a)*B is given its item colour F = (c - (1-a)*B) / a. Where alpha is too
     * low for that to be stable, F leans toward the local item colour instead. Returns how many pixels changed.
     */
    fun decontaminate(px: IntArray, w: Int, h: Int, colors: EdgeColors): Int {
        var changed = 0
        val sx = colors.width.toFloat() / w
        val sy = colors.height.toFloat() / h
        for (y in 0 until h) {
            val my = min(colors.height - 1, ((y + 0.5f) * sy).toInt())
            for (x in 0 until w) {
                val i = y * w + x
                val c = px[i]
                val a8 = c ushr 24
                if (a8 <= 5 || a8 >= 250) continue
                val mx = min(colors.width - 1, ((x + 0.5f) * sx).toInt())
                val lx = mx - colors.x0
                val ly = my - colors.y0
                if (lx !in 0 until colors.cw || ly !in 0 until colors.ch) continue
                val mi = 3 * (ly * colors.cw + lx)
                val bR = colors.bg[mi]
                if (bR.isNaN()) continue // no clear background nearby (e.g. a hole inside the item): leave it
                val a = a8 / 255f
                // Trust the unmixing more as alpha rises; below ~0.15 it's mostly the local item colour.
                val t = ((a - 0.15f) / 0.35f).coerceIn(0f, 1f)
                val fR = colors.fg[mi]
                var nc = c and 0xFF000000.toInt()
                for ((k, shift) in intArrayOf(16, 8, 0).withIndex()) {
                    val ci = ((c shr shift) and 0xFF).toFloat()
                    val unmixed = ((ci - (1f - a) * colors.bg[mi + k]) / a).coerceIn(0f, 255f)
                    val v = if (fR.isNaN()) unmixed else t * unmixed + (1f - t) * colors.fg[mi + k]
                    nc = nc or ((v + 0.5f).toInt().coerceIn(0, 255) shl shift)
                }
                if (nc != c) { px[i] = nc; changed++ }
            }
        }
        return changed
    }
}
