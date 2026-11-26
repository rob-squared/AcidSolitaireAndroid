package com.acidsolitaire.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Decodes an image no larger than [side] pixels on its longest edge, honouring its rotation. */
fun decodeScaled(source: ImageDecoder.Source, side: Int): Bitmap? = try {
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longest = max(info.size.width, info.size.height)
        if (longest > side) {
            val f = side.toFloat() / longest
            decoder.setTargetSize(
                max(1, (info.size.width * f).roundToInt()), max(1, (info.size.height * f).roundToInt())
            )
        }
    }
} catch (e: Exception) {
    null
}

data class AlbumSummary(val id: String, val title: String, val count: Int, val newestMediaId: Long)

/** Lists the device's photo albums (MediaStore "buckets") and loads their photos. */
object AlbumLibrary {
    private data class Row(val mediaId: Long, val bucketId: String, val bucketName: String)

    fun permissionsToRequest(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 ->
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** True when the app may read at least some photos (full or "selected photos" access). */
    fun hasAccess(context: Context): Boolean = permissionsToRequest().any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** All photos, newest first. */
    private fun rows(context: Context): List<Row> {
        if (!hasAccess(context)) return emptyList()
        val result = mutableListOf<Row>()
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            )
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val bucketCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                while (c.moveToNext()) {
                    result += Row(c.getLong(idCol), c.getString(bucketCol) ?: "0", c.getString(nameCol) ?: "Unnamed")
                }
            }
        } catch (e: SecurityException) {
            return emptyList()
        }
        return result
    }

    /** Every album that holds at least one photo, sorted by name. */
    fun albums(context: Context): List<AlbumSummary> =
        rows(context).groupBy { it.bucketId }
            .map { (id, items) -> AlbumSummary(id, items.first().bucketName, items.size, items.first().mediaId) }
            .sortedBy { it.title.lowercase() }

    /** Ids ("asset:<media id>") of every photo in the given albums. */
    fun assetIds(context: Context, albumIds: List<String>): List<String> {
        if (albumIds.isEmpty()) return emptyList()
        val wanted = albumIds.toSet()
        return rows(context).filter { it.bucketId in wanted }.map { "asset:${it.mediaId}" }.distinct()
    }

    fun uri(mediaId: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)

    fun load(context: Context, mediaId: Long, side: Int): Bitmap? =
        decodeScaled(ImageDecoder.createSource(context.contentResolver, uri(mediaId)), side)
}

/**
 * Keeps the built-in backgrounds, photos the player imported, and whole photo albums. One
 * background is shared by all three games. Once the player has chosen any photos or albums, only
 * those are used (the built-in artwork steps aside), and each win picks a random new one.
 */
class BackgroundStore(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val directory: File = File(context.filesDir, "backgrounds"),
) {
    private companion object {
        const val PHOTOS = "backgrounds.photos.v1"
        const val ASSIGNMENTS = "backgrounds.assignments.v2"
        const val ROTATE = "backgrounds.rotate.v1"
        const val ALBUMS = "backgrounds.albums.v1"
        const val SHARED_KEY = "all"
        const val BUILT_IN = "builtin:"
        const val PHOTO = "photo:"
        const val ASSET = "asset:"
    }

    var photoIDs by mutableStateOf<List<String>>(emptyList())
        private set
    var albumIDs by mutableStateOf<List<String>>(emptyList())
        private set
    var assignments by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var rotateAfterWin by mutableStateOf(true)
        private set

    /** Bumped when the available photos change without the assignments changing. */
    private var revision by mutableStateOf(0)

    private var albumAssetCache: List<String>? = null
    private val images = LinkedHashMap<String, ImageBitmap>()
    private val thumbnails = HashMap<String, ImageBitmap>()

    init {
        directory.mkdirs()
        photoIDs = readList(PHOTOS).filter { File(directory, fileName(it)).exists() }
        albumIDs = readList(ALBUMS)
        assignments = readAssignments()
        rotateAfterWin = prefs.getBoolean(ROTATE, true)
    }

    // region Lookup

    val builtInIDs: List<String> get() = GameKind.entries.map { BUILT_IN + it.key }

    /** Identifiers of every photo in the chosen albums. Looked up once, then kept. */
    val albumAssetIDs: List<String>
        get() = albumAssetCache ?: AlbumLibrary.assetIds(context, albumIDs).also { albumAssetCache = it }

    /** True once the player has chosen any photos or albums; the built-in artwork is then unused. */
    val hasUserBackgrounds: Boolean
        get() = revision >= 0 && (photoIDs.isNotEmpty() || albumAssetIDs.isNotEmpty())

    /** What the background grid offers: the built-in artwork, or the player's own photos. */
    val gridIDs: List<String> get() = if (hasUserBackgrounds) photoIDs else builtInIDs

    fun defaultId(kind: GameKind) = BUILT_IN + kind.key

    /** [kind] only matters before anything is chosen, when each game shows its own built-in art. */
    fun currentId(kind: GameKind): String {
        val assigned = assignments[SHARED_KEY]
        if (assigned != null && isValid(assigned)) return assigned
        return fallbackId(kind)
    }

    fun isPhoto(id: String) = id.startsWith(PHOTO)
    fun isAsset(id: String) = id.startsWith(ASSET)
    fun builtInKey(id: String): String? = if (id.startsWith(BUILT_IN)) id.removePrefix(BUILT_IN) else null

    private fun isValid(id: String): Boolean = when {
        isPhoto(id) -> id in photoIDs
        isAsset(id) -> albumIDs.isNotEmpty()
        else -> !hasUserBackgrounds && id in builtInIDs
    }

    /** A steady choice when nothing valid is selected yet. */
    private fun fallbackId(kind: GameKind): String {
        if (!hasUserBackgrounds) return defaultId(kind)
        if (photoIDs.isNotEmpty()) return photoIDs[0]
        return albumAssetIDs.firstOrNull() ?: defaultId(kind)
    }

    // endregion

    // region Images

    /** Loads a photo or album photo for display. Returns null if it can't be loaded. */
    suspend fun loadImage(id: String): ImageBitmap? {
        synchronized(images) { images[id]?.let { return it } }
        val bitmap = withContext(Dispatchers.IO) {
            when {
                isPhoto(id) -> {
                    val file = File(directory, fileName(id))
                    if (file.exists()) decodeScaled(ImageDecoder.createSource(file), 2400) else null
                }
                isAsset(id) -> id.removePrefix(ASSET).toLongOrNull()?.let { AlbumLibrary.load(context, it, 2400) }
                else -> null
            }
        } ?: return null
        val image = bitmap.asImageBitmap()
        synchronized(images) {
            // Only a few full-size images are kept in memory at once.
            while (images.size >= 3) images.remove(images.keys.first())
            images[id] = image
        }
        return image
    }

    suspend fun thumbnail(id: String): ImageBitmap? {
        thumbnails[id]?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            if (isPhoto(id)) {
                val file = File(directory, fileName(id))
                if (file.exists()) decodeScaled(ImageDecoder.createSource(file), 320) else null
            } else {
                null
            }
        } ?: return null
        return bitmap.asImageBitmap().also { thumbnails[id] = it }
    }

    /** Forgets an album photo that can no longer be loaded, so it isn't picked again. */
    fun dropUnavailableAsset(id: String) {
        albumAssetCache = albumAssetCache?.filter { it != id }
        revision += 1
        saveAssignments(assignments.filterValues { it != id })
    }

    // endregion

    // region Changes

    fun select(id: String) {
        if (!isValid(id)) return
        saveAssignments(assignments + (SHARED_KEY to id))
    }

    /**
     * Gives every game a random different background from the player's photos and albums, or from
     * the built-in artwork when the player has chosen none.
     */
    fun shuffle(kind: GameKind) {
        val current = currentId(kind)
        val candidates = if (hasUserBackgrounds) photoIDs + albumAssetIDs else builtInIDs
        if (candidates.size < 2) return
        var next = current
        while (next == current) next = candidates.random()
        select(next)
    }

    /** After a win the background moves to a random new one, if that setting is on. */
    fun advanceAfterWin(kind: GameKind) {
        if (rotateAfterWin) shuffle(kind)
    }

    fun updateRotateAfterWin(value: Boolean) {
        rotateAfterWin = value
        prefs.edit().putBoolean(ROTATE, value).apply()
    }

    /**
     * Imports photos one at a time (as downscaled JPEGs), so a large selection never sits in memory
     * at once. The first one imported is applied when [applyFirst] is set. Returns how many worked.
     */
    suspend fun importPhotos(uris: List<Uri>, applyFirst: Boolean, onProgress: (Int, Int) -> Unit): Int {
        var added = 0
        for ((index, uri) in uris.withIndex()) {
            onProgress(index + 1, uris.size)
            val id = withContext(Dispatchers.IO) { importOne(uri) } ?: continue
            photoIDs = photoIDs + id
            prefs.edit().putString(PHOTOS, JSONArray(photoIDs).toString()).apply()
            if (added == 0 && applyFirst) select(id)
            added += 1
        }
        return added
    }

    private fun importOne(uri: Uri): String? {
        val bitmap = decodeScaled(ImageDecoder.createSource(context.contentResolver, uri), 2400) ?: return null
        val name = UUID.randomUUID().toString()
        return try {
            FileOutputStream(File(directory, "$name.jpg")).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            PHOTO + name
        } catch (e: Exception) {
            null
        }
    }

    fun removePhoto(id: String) {
        if (!isPhoto(id)) return
        File(directory, fileName(id)).delete()
        synchronized(images) { images.remove(id) }
        thumbnails.remove(id)
        photoIDs = photoIDs.filter { it != id }
        prefs.edit().putString(PHOTOS, JSONArray(photoIDs).toString()).apply()
        saveAssignments(assignments.filterValues { it != id })
    }

    /**
     * Replaces the set of whole albums used as backgrounds. When albums were added and
     * [applyRandom] is set, the background becomes a random photo from them.
     */
    fun setAlbums(ids: List<String>, applyRandom: Boolean) {
        val previous = albumIDs.toSet()
        albumIDs = ids
        prefs.edit().putString(ALBUMS, JSONArray(ids).toString()).apply()
        albumAssetCache = null
        synchronized(images) { images.keys.removeAll { isAsset(it) } }
        revision += 1
        val valid = albumAssetIDs.toSet()
        saveAssignments(assignments.filterValues { !isAsset(it) || it in valid })
        if (applyRandom && (ids.toSet() - previous).isNotEmpty()) {
            albumAssetIDs.randomOrNull()?.let { select(it) }
        }
    }

    fun removeAlbum(id: String) = setAlbums(albumIDs.filter { it != id }, false)

    // endregion

    private fun fileName(id: String) = id.removePrefix(PHOTO) + ".jpg"

    private fun readList(key: String): List<String> {
        val text = prefs.getString(key, null) ?: return emptyList()
        return try {
            val array = JSONArray(text)
            List(array.length()) { array.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun readAssignments(): Map<String, String> {
        val text = prefs.getString(ASSIGNMENTS, null) ?: return emptyMap()
        return try {
            val o = JSONObject(text)
            o.keys().asSequence().associateWith { o.getString(it) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun saveAssignments(map: Map<String, String>) {
        assignments = map
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        prefs.edit().putString(ASSIGNMENTS, o.toString()).apply()
    }
}
