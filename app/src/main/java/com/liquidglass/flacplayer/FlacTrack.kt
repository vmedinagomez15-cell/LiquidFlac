package com.liquidglass.flacplayer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FlacTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val contentUri: Uri,
    val albumArtUri: Uri,
    val sizeBytes: Long,
    val bitrateKbps: Int,
    val displayName: String
)

object FlacScanner {
    suspend fun scanFlacFiles(context: Context): List<FlacTrack> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<FlacTrack>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.BITRATE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.MIME_TYPE
        )

        // Filtra especificamente archivos .flac o MIME audio/flac y audio/x-flac
        val selection = "${MediaStore.Audio.Media.MIME_TYPE} IN (?, ?) OR LOWER(${MediaStore.Audio.Media.DISPLAY_NAME}) LIKE ?"
        val selectionArgs = arrayOf("audio/flac", "audio/x-flac", "%.flac")
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val bitrateCol = cursor.getColumnIndex(MediaStore.Audio.Media.BITRATE)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)

            val artworkBaseUri = Uri.parse("content://media/external/audio/albumart")

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val rawName = cursor.getString(nameCol) ?: "Audio.flac"
                val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() }
                    ?: rawName.removeSuffix(".flac").removeSuffix(".FLAC")
                val artist = cursor.getString(artistCol)?.takeIf { it != "<unknown>" && it.isNotBlank() }
                    ?: "Artista Desconocido"
                val album = cursor.getString(albumCol)?.takeIf { it != "<unknown>" && it.isNotBlank() }
                    ?: "Álbum Hi-Res"
                val albumId = cursor.getLong(albumIdCol)
                val duration = cursor.getLong(durationCol)
                val size = cursor.getLong(sizeCol)
                val bitrate = if (bitrateCol != -1) (cursor.getInt(bitrateCol) / 1000) else 0

                val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                val albumArtUri = ContentUris.withAppendedId(artworkBaseUri, albumId)

                val estimatedBitrate = if (bitrate > 0) bitrate else if (duration > 0) {
                    ((size * 8) / duration).toInt()
                } else 900

                tracks.add(
                    FlacTrack(
                        id = id,
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = duration,
                        contentUri = contentUri,
                        albumArtUri = albumArtUri,
                        sizeBytes = size,
                        bitrateKbps = estimatedBitrate,
                        displayName = rawName
                    )
                )
            }
        }
        tracks
    }
}
