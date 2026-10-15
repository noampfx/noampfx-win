package com.noamp.fx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One audio file found in the music folders. */
class Song(val id: Long, val file: File, val title: String, val artist: String, val album: String, val genre: String, val folder: String, val durMs: Long)

class Playlist(var name: String, val uris: MutableList<String>)

/** Audio files of the chosen music folders (MP3 / WAV / FLAC) + the user's playlists. */
object MusicLibrary {
    private val EXT = setOf("mp3", "wav", "flac")

    var songs by mutableStateOf<List<Song>>(emptyList())
    var scanning by mutableStateOf(false)
    var scanned by mutableStateOf(false)
    val folders = mutableStateListOf<String>()
    val playlists = mutableStateListOf<Playlist>()
    private var byPath: Map<String, Song> = emptyMap()
    private var loadedPl = false
    private var loadedFolders = false

    fun find(path: String): Song? = byPath[path]

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("music", Context.MODE_PRIVATE)

    fun loadFolders(ctx: Context) {
        if (loadedFolders) return
        loadedFolders = true
        val saved = prefs(ctx).getString("folders", null)
        val list = if (saved == null) listOf(File(System.getProperty("user.home"), "Music").path) else saved.split("\n").filter { it.isNotBlank() }
        folders.clear(); folders.addAll(list.filter { File(it).isDirectory })
    }
    fun addFolder(ctx: Context, dir: File) {
        if (!dir.isDirectory || folders.any { it.equals(dir.path, true) }) return
        folders.add(dir.path); prefs(ctx).edit().putString("folders", folders.joinToString("\n")).apply(); scanned = false
    }
    fun removeFolder(ctx: Context, path: String) {
        folders.remove(path); prefs(ctx).edit().putString("folders", folders.joinToString("\n")).apply(); scanned = false
    }

    /** Walks the music folders and reads the tags (title, artist, album, genre) of every supported file. */
    suspend fun scan(ctx: Context) {
        if (scanning) return
        scanning = true
        try {
            loadFolders(ctx)
            val roots = folders.toList()
            val list = withContext(Dispatchers.IO) { query(roots) }
            songs = list; byPath = list.associateBy { it.file.path }; scanned = true
        } finally { scanning = false }
    }

    private fun query(roots: List<String>): List<Song> {
        val out = ArrayList<Song>(); var id = 0L
        for (r in roots) {
            val root = File(r)
            root.walkTopDown().onEnter { it.name != "System Volume Information" && !it.name.startsWith("$") }.forEach { f ->
                if (!f.isFile || f.extension.lowercase() !in EXT) return@forEach
                val t = TagReader.read(f, false)
                val dir = f.parentFile
                out.add(Song(id++, f, t?.title ?: f.nameWithoutExtension.replace('_', ' '), t?.artist ?: "Unknown artist", t?.album ?: (dir?.name ?: "Unknown album"),
                    t?.genre ?: "Unknown genre", dir?.path ?: "Other", 0L))
            }
        }
        return out.sortedBy { it.title.lowercase() }
    }

    fun loadPlaylists(ctx: Context) {
        if (loadedPl) return
        loadedPl = true
        try {
            val arr = JSONArray(prefs(ctx).getString("playlists", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i); val ua = o.getJSONArray("uris")
                playlists.add(Playlist(o.getString("name"), MutableList(ua.length()) { ua.getString(it) }))
            }
        } catch (_: Exception) { }
    }
    private fun savePlaylists(ctx: Context) {
        val arr = JSONArray()
        playlists.forEach { p -> arr.put(JSONObject().put("name", p.name).put("uris", JSONArray(p.uris))) }
        prefs(ctx).edit().putString("playlists", arr.toString()).apply()
    }
    fun createPlaylist(ctx: Context, name: String): Playlist {
        val p = Playlist(name.trim().ifBlank { "Playlist ${playlists.size + 1}" }, mutableListOf())
        playlists.add(p); savePlaylists(ctx); return p
    }
    fun addToPlaylist(ctx: Context, p: Playlist, s: Song) { if (s.file.path !in p.uris) { p.uris.add(s.file.path); touch(ctx, p) } }
    fun removeFromPlaylist(ctx: Context, p: Playlist, uri: String) { p.uris.remove(uri); touch(ctx, p) }
    fun deletePlaylist(ctx: Context, p: Playlist) { playlists.remove(p); savePlaylists(ctx) }

    /** Playlist lists are plain mutable lists: replace the entry so Compose notices the change. */
    private fun touch(ctx: Context, p: Playlist) { val i = playlists.indexOf(p); if (i >= 0) playlists[i] = Playlist(p.name, p.uris); savePlaylists(ctx) }
}
