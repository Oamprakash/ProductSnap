package com.oamsapps.productsnap.image

/**
 * One-tap export shapes per marketplace. Checked 18 Oct 2026:
 *  - eBay (help: picture policy): at least 500 px on the longest side; no added borders, text, artwork or
 *    watermarks of any kind. Any shape is accepted; square is shown best in search.
 *  - Etsy (help: image requirements): at least 2000 px on each side recommended, first photo landscape or square,
 *    files over about 1 MB may fail to upload, transparent PNGs show as black.
 *  - Vinted (help: what photos to upload): your own photos, no filters, the whole item in the first photo. Its
 *    portrait 4:5 feed shape comes from cross-listing tools; Vinted doesn't publish one.
 *  - Poshmark and Depop: square, per cross-listing tools (Closo, Crosslist); neither publishes sizes.
 * Marketplaces change these; re-check before each release.
 */
enum class Marketplace(
    val label: String,
    /** Width / height of the exported image. */
    val aspect: Float,
    val width: Int,
    val height: Int,
    /** Keep the seller's real background (light and crop only). */
    val natural: Boolean,
    val jpegQuality: Int,
    val note: String,
) {
    EBAY("eBay", 1f, 2048, 2048, false, 95,
        "Square, 2048 px. eBay doesn't allow watermarks, borders or added text."),
    POSHMARK("Poshmark", 1f, 2048, 2048, false, 95,
        "Square, the shape Poshmark shows."),
    DEPOP("Depop", 1f, 2048, 2048, false, 95,
        "Square, the shape Depop shows."),
    ETSY("Etsy", 4f / 3f, 2667, 2000, false, 85,
        "Landscape 4:3, 2000 px tall as Etsy recommends, saved small enough to upload easily."),
    VINTED("Vinted", 4f / 5f, 1600, 2000, true, 95,
        "Portrait 4:5 with your real background, lit and framed. Vinted asks for your own photos without filters.");

    companion object {
        fun fromName(name: String?) = entries.firstOrNull { it.name == name }
    }
}
