package com.oamsapps.productsnap.batch

import android.content.Context
import com.oamsapps.productsnap.image.Adjustments
import com.oamsapps.productsnap.image.BatchCrop
import com.oamsapps.productsnap.image.ExportSettings
import com.oamsapps.productsnap.image.LightStrength
import com.oamsapps.productsnap.image.Marketplace
import com.oamsapps.productsnap.image.background.BackgroundPresets
import com.oamsapps.productsnap.image.background.BackgroundStyle
import com.oamsapps.productsnap.image.background.backgroundFromJson
import com.oamsapps.productsnap.image.background.toJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** NEEDS_LOOK: a product was found but the cutout is unusable (garbled or the whole photo); fix it in the editor. */
enum class StoredStatus { PENDING, WORKING, SAVED, NO_SUBJECT, NEEDS_LOOK, FAILED }

data class StoredItem(
    val id: Int,
    val inputPath: String,      // private copy of the photo, so the job never depends on URI grants
    val sourceUri: String,      // only used to skip duplicates when adding
    val status: StoredStatus = StoredStatus.PENDING,
    val reason: String? = null,
    /** [PhotoWarning] names found while processing; the photo is still saved, and its copy kept to open later. */
    val warnings: List<String> = emptyList(),
)

data class BatchSettings(
    val removeBackground: Boolean = true,
    val background: BackgroundStyle = BackgroundPresets.WHITE,
    val squareCanvas: Boolean = true,
    val adjustments: Adjustments = Adjustments(),
    val crop: BatchCrop = BatchCrop.NONE,
    val shadow: Boolean = true,
    /** Auto light level for each photo's measured light fix (only with background removal). */
    val lightStrength: LightStrength = LightStrength.DEFAULT,
    /** Shape and size for a marketplace; overrides [squareCanvas]. */
    val marketplace: Marketplace? = null,
) {
    fun toExport(premium: Boolean) = ExportSettings(
        removeBackground = removeBackground,
        background = background,
        squareCanvas = squareCanvas,
        adjustments = adjustments,
        premium = premium,
        crop = crop,
        shadow = shadow,
        lightStrength = lightStrength,
        marketplace = marketplace,
    )
}

data class BatchJob(
    val items: List<StoredItem> = emptyList(),
    val settings: BatchSettings = BatchSettings(),
    val premium: Boolean = false,       // frozen when a run starts
    /** Where the last run's time went, e.g. "10 photos in 31.2 s…"; shown under the progress bar. */
    val timing: String? = null,
) {
    val nextId get() = (items.maxOfOrNull { it.id } ?: -1) + 1
    val savedCount get() = items.count { it.status == StoredStatus.SAVED }
    val finishedCount get() = items.count { it.status != StoredStatus.PENDING && it.status != StoredStatus.WORKING }

    fun withStatus(id: Int, status: StoredStatus, reason: String? = null) =
        copy(items = items.map { if (it.id == id) it.copy(status = status, reason = reason) else it })

    fun withWarning(id: Int, warning: String) =
        copy(items = items.map { if (it.id == id && warning !in it.warnings) it.copy(warnings = it.warnings + warning) else it })
}

/**
 * The batch lives on disk, not in the ViewModel, so it survives the process being killed.
 * The worker and the UI share this one instance (same process), guarded by a mutex.
 */
class BatchStore private constructor(context: Context) {

    private val dir = File(context.filesDir, "batch").apply { mkdirs() }
    private val inputsDir = File(dir, "inputs").apply { mkdirs() }
    private val file = File(dir, "job.json")
    private val mutex = Mutex()
    private var cache: BatchJob? = null

    suspend fun read(): BatchJob = mutex.withLock { load() }

    suspend fun update(change: (BatchJob) -> BatchJob): BatchJob = mutex.withLock {
        val next = change(load())
        write(next)
        cache = next
        next
    }

    fun inputFile(id: Int) = File(inputsDir, "photo_$id")

    /** Removes copies that no longer belong to any item. */
    suspend fun pruneInputs() = withContext(Dispatchers.IO) {
        val keep = read().items.map { it.inputPath }.toSet()
        inputsDir.listFiles()?.filter { it.absolutePath !in keep }?.forEach { it.delete() }
    }

    private suspend fun load(): BatchJob {
        cache?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            if (file.exists()) runCatching { decode(JSONObject(file.readText())) }.getOrNull() else null
        } ?: BatchJob()
        cache = loaded
        return loaded
    }

    private suspend fun write(job: BatchJob) = withContext(Dispatchers.IO) {
        val tmp = File(dir, "job.json.tmp")
        tmp.writeText(encode(job).toString())
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }

    private fun encode(job: BatchJob) = JSONObject().apply {
        put("premium", job.premium)
        job.timing?.let { put("timing", it) }
        put("settings", JSONObject().apply {
            put("removeBackground", job.settings.removeBackground)
            put("background", job.settings.background.toJson())
            put("squareCanvas", job.settings.squareCanvas)
            put("brightness", job.settings.adjustments.brightness.toDouble())
            put("contrast", job.settings.adjustments.contrast.toDouble())
            put("crop", job.settings.crop.name)
            put("shadow", job.settings.shadow)
            put("lightStrength", job.settings.lightStrength.name)
            job.settings.marketplace?.let { put("marketplace", it.name) }
        })
        put("items", JSONArray().apply {
            job.items.forEach { item ->
                put(JSONObject().apply {
                    put("id", item.id)
                    put("inputPath", item.inputPath)
                    put("sourceUri", item.sourceUri)
                    put("status", item.status.name)
                    item.reason?.let { put("reason", it) }
                    if (item.warnings.isNotEmpty()) put("warnings", JSONArray(item.warnings))
                })
            }
        })
    }

    private fun decode(json: JSONObject): BatchJob {
        val s = json.getJSONObject("settings")
        val settings = BatchSettings(
            removeBackground = s.getBoolean("removeBackground"),
            // Older saves only had a colour.
            background = backgroundFromJson(s.optJSONObject("background"))
                ?: BackgroundStyle.Solid(s.optInt("backgroundColor", 0xFFFFFFFF.toInt())),
            squareCanvas = s.getBoolean("squareCanvas"),
            adjustments = Adjustments(
                brightness = s.getDouble("brightness").toFloat(),
                contrast = s.getDouble("contrast").toFloat(),
            ),
            crop = runCatching { BatchCrop.valueOf(s.optString("crop", "NONE")) }.getOrDefault(BatchCrop.NONE),
            shadow = s.optBoolean("shadow", true),
            // Builds before the levels had an on/off switch.
            marketplace = Marketplace.fromName(s.optString("marketplace", "")),
            lightStrength = if (s.has("lightStrength")) LightStrength.fromName(s.getString("lightStrength"))
                else if (!s.optBoolean("autoLight", true)) LightStrength.OFF else LightStrength.DEFAULT,
        )
        val arr = json.getJSONArray("items")
        val items = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            StoredItem(
                id = o.getInt("id"),
                inputPath = o.getString("inputPath"),
                sourceUri = o.optString("sourceUri"),
                status = runCatching { StoredStatus.valueOf(o.getString("status")) }.getOrDefault(StoredStatus.PENDING),
                reason = if (o.has("reason")) o.getString("reason") else null,
                warnings = o.optJSONArray("warnings")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            )
        }
        return BatchJob(
            items = items,
            settings = settings,
            premium = json.optBoolean("premium"),
            timing = if (json.has("timing")) json.getString("timing") else null,
        )
    }

    companion object {
        @Volatile private var instance: BatchStore? = null

        fun get(context: Context): BatchStore =
            instance ?: synchronized(this) {
                instance ?: BatchStore(context.applicationContext).also { instance = it }
            }
    }
}
