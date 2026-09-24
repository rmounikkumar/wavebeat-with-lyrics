package com.wavebeat

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * Locates and reads synchronized lyrics (.lrc) files that are stored next to the
 * audio file — the same convention shown in the tutorial video:
 *   song.mp3   ->   song.lrc   (same folder, same base name)
 */
object LyricsFileHelper {

    /**
     * Resolves the on-disk path of a song via the MediaStore DATA column so we
     * can look for a sibling .lrc file. Returns null when unavailable.
     */
    private fun resolveSongFilePath(songUri: Uri, contentResolver: ContentResolver): String? {
        return try {
            val id = songUri.lastPathSegment?.toLongOrNull() ?: return null
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
            val projection = arrayOf(MediaStore.Audio.Media.DATA)
            val selection = "${MediaStore.Audio.Media._ID} = ?"
            val args = arrayOf(id.toString())
            contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val col = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                    if (col >= 0) cursor.getString(col) else null
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Returns a Uri for an .lrc file that sits next to the song and shares its
     * base name, or null when there is none.
     */
    fun findLrcUri(songUri: Uri, contentResolver: ContentResolver): Uri? {
        val path = resolveSongFilePath(songUri, contentResolver) ?: return null
        return try {
            val songFile = File(path)
            val parent = songFile.parentFile ?: return null
            val base = songFile.nameWithoutExtension
            val lrcFile = File(parent, "$base.lrc")
            if (lrcFile.isFile) Uri.fromFile(lrcFile) else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Reads the full text of a (small) file. LRC files are tiny, so reading them
     * fully into memory is fine. Falls back to a lenient decoding when the file
     * is not valid UTF-8.
     */
    fun readText(uri: Uri, contentResolver: ContentResolver): String? {
        return try {
            val pfd = contentResolver.openAssetFileDescriptor(uri, "r") ?: return null
            pfd.use { p ->
                val input = p.createInputStream()
                input.use { ins ->
                    val bytes = ins.readBytes()
                    val utf8 = String(bytes, Charsets.UTF_8)
                    if (utf8.contains('\uFFFD')) {
                        String(bytes, Charsets.ISO_8859_1)
                    } else {
                        utf8
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}