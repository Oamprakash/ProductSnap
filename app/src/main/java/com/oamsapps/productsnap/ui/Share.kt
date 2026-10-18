package com.oamsapps.productsnap.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri

/**
 * The Android share sheet for saved photos, so they can go straight into a marketplace app (eBay, Vinted, ...).
 * The photos are gallery entries; read access is granted to whichever app is picked.
 */
fun shareIntent(uris: List<Uri>): Intent {
    require(uris.isNotEmpty())
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    send.type = "image/*"
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    return Intent.createChooser(send, "Share to").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
