
package com.lifonkmp3

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PlaylistSong(
    val id: Long,
    val title: String,
    val artist: String,
    val duration: Long,
    val uri: String
)

data class SavedPlaylist(
    val id: String,
    val name: String,
    val songs: List<PlaylistSong>
)

object PlaylistStorage {

    private const val PREFS_NAME = "lifonk_playlists"
    private const val KEY_PLAYLISTS = "playlists"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    fun load(context: Context): List<SavedPlaylist> {
        val raw = prefs(context).getString(KEY_PLAYLISTS, "[]")
            ?: "[]"

        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val songsJson = item.optJSONArray("songs")
                        ?: JSONArray()

                    val songs = buildList {
                        for (j in 0 until songsJson.length()) {
                            val song = songsJson.getJSONObject(j)

                            add(
                                PlaylistSong(
                                    id = song.optLong("id"),
                                    title = song.optString("title"),
                                    artist = song.optString("artist"),
                                    duration = song.optLong("duration"),
                                    uri = song.optString("uri")
                                )
                            )
                        }
                    }

                    add(
                        SavedPlaylist(
                            id = item.optString("id"),
                            name = item.optString("name"),
                            songs = songs
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(
        context: Context,
        playlists: List<SavedPlaylist>
    ) {
        val array = JSONArray()

        playlists.forEach { playlist ->
            val songsArray = JSONArray()

            playlist.songs.forEach { song ->
                songsArray.put(
                    JSONObject().apply {
                        put("id", song.id)
                        put("title", song.title)
                        put("artist", song.artist)
                        put("duration", song.duration)
                        put("uri", song.uri)
                    }
                )
            }

            array.put(
                JSONObject().apply {
                    put("id", playlist.id)
                    put("name", playlist.name)
                    put("songs", songsArray)
                }
            )
        }

        prefs(context).edit()
            .putString(KEY_PLAYLISTS, array.toString())
            .apply()
    }

    fun create(
        context: Context,
        name: String
    ): SavedPlaylist? {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return null

        val playlists = load(context)

        if (playlists.any {
                it.name.equals(cleanName, ignoreCase = true)
            }
        ) {
            return null
        }

        val playlist = SavedPlaylist(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            songs = emptyList()
        )

        save(context, playlists + playlist)
        return playlist
    }

    fun rename(
        context: Context,
        playlistId: String,
        newName: String
    ): Boolean {
        val cleanName = newName.trim()
        if (cleanName.isBlank()) return false

        val playlists = load(context)

        if (playlists.any {
                it.id != playlistId &&
                        it.name.equals(cleanName, ignoreCase = true)
            }
        ) {
            return false
        }

        if (playlists.none { it.id == playlistId }) {
            return false
        }

        save(
            context,
            playlists.map {
                if (it.id == playlistId) {
                    it.copy(name = cleanName)
                } else {
                    it
                }
            }
        )

        return true
    }

    fun delete(
        context: Context,
        playlistId: String
    ) {
        save(
            context,
            load(context).filterNot { it.id == playlistId }
        )
    }

    fun addSong(
        context: Context,
        playlistId: String,
        song: PlaylistSong
    ): Boolean {
        val playlists = load(context)
        val playlist = playlists.find { it.id == playlistId }
            ?: return false

        // Avoid adding the same song twice.
        if (playlist.songs.any { it.uri == song.uri }) {
            return false
        }

        val updated = playlist.copy(
            songs = playlist.songs + song
        )

        save(
            context,
            playlists.map {
                if (it.id == playlistId) updated else it
            }
        )

        return true
    }

    fun removeSong(
        context: Context,
        playlistId: String,
        songUri: String
    ): Boolean {
        val playlists = load(context)
        val playlist = playlists.find { it.id == playlistId }
            ?: return false

        val updated = playlist.copy(
            songs = playlist.songs.filterNot {
                it.uri == songUri
            }
        )

        save(
            context,
            playlists.map {
                if (it.id == playlistId) updated else it
            }
        )

        return true
    }

    fun moveSong(
        context: Context,
        playlistId: String,
        fromIndex: Int,
        toIndex: Int
    ): Boolean {
        val playlists = load(context)
        val playlist = playlists.find { it.id == playlistId }
            ?: return false

        if (fromIndex !in playlist.songs.indices ||
            toIndex !in playlist.songs.indices
        ) {
            return false
        }

        val reordered = playlist.songs.toMutableList()
        val song = reordered.removeAt(fromIndex)
        reordered.add(toIndex, song)

        val updated = playlist.copy(songs = reordered)

        save(
            context,
            playlists.map {
                if (it.id == playlistId) updated else it
            }
        )

        return true
    }
}
