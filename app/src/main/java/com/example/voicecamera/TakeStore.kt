package com.example.voicecamera

import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/** One recorded video or one photo taken by the app. */
data class Take(
    val kind: Kind,
    val id: Long,
    val uri: Uri,
    val dateAddedSeconds: Long,
    val durationMs: Long,        // videos only
    val sizeBytes: Long,
    val loved: Boolean
) {
    enum class Kind { VIDEO, PHOTO }

    val isVideo: Boolean get() = kind == Kind.VIDEO

    /** Videos and photos have separate id numbers in the media library, so the heart is remembered under "v12" / "p12". */
    val key: String get() = (if (isVideo) "v" else "p") + id
}

/**
 * Finds the videos and photos this app took (Movies/VoiceCamera and Pictures/VoiceCamera) and remembers which
 * ones are marked with a heart. The files live in the phone's media library, so they also show up in the Gallery.
 */
class TakeStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("takes", Context.MODE_PRIVATE)

    /** Videos and photos together, newest first. */
    fun loadTakes(): List<Take> {
        val loved = lovedKeys()
        val takes = mutableListOf<Take>()
        takes += query(
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            folder = VIDEO_FOLDER,
            kind = Take.Kind.VIDEO,
            hasDuration = true,
            loved = loved
        )
        takes += query(
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            folder = PHOTO_FOLDER,
            kind = Take.Kind.PHOTO,
            hasDuration = false,
            loved = loved
        )
        return takes.sortedWith(compareByDescending<Take> { it.dateAddedSeconds }.thenByDescending { it.id })
    }

    private fun query(collection: Uri, folder: String, kind: Take.Kind, hasDuration: Boolean, loved: Set<String>): List<Take> {
        val result = mutableListOf<Take>()
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.SIZE)
            if (hasDuration) add(MediaStore.Video.Media.DURATION)
        }.toTypedArray()
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(folder),
            null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val durCol = if (hasDuration) cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION) else -1
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val take = Take(
                    kind = kind,
                    id = id,
                    uri = Uri.withAppendedPath(collection, id.toString()),
                    dateAddedSeconds = cursor.getLong(dateCol),
                    durationMs = if (durCol >= 0) cursor.getLong(durCol) else 0L,
                    sizeBytes = cursor.getLong(sizeCol),
                    loved = false
                )
                result += take.copy(loved = take.key in loved)
            }
        }
        return result
    }

    fun count(): Int = loadTakes().size

    /** Hearts are stored as "v12" / "p12"; the first version stored plain video ids, which are read as videos. */
    fun lovedKeys(): Set<String> = prefs.getStringSet(KEY_LOVED, emptySet()).orEmpty()
        .map { if (it.firstOrNull()?.isDigit() == true) "v$it" else it }
        .toSet()

    fun setLoved(key: String, loved: Boolean) {
        val all = lovedKeys().toMutableSet()
        if (loved) all += key else all -= key
        prefs.edit().putStringSet(KEY_LOVED, all).apply()
    }

    /** Drop the heart for items that no longer exist. */
    fun forget(keys: Collection<String>) {
        val all = lovedKeys().toMutableSet()
        all.removeAll(keys.toSet())
        prefs.edit().putStringSet(KEY_LOVED, all).apply()
    }

    companion object {
        const val VIDEO_FOLDER = "Movies/VoiceCamera/"
        const val PHOTO_FOLDER = "Pictures/VoiceCamera/"
        private const val KEY_LOVED = "loved"
    }
}
