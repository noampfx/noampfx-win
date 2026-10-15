package com.noamp.fx.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.noamp.fx.MusicLibrary
import com.noamp.fx.Playlist
import com.noamp.fx.Rig
import com.noamp.fx.Song
import kotlinx.coroutines.launch
import kotlin.random.Random

private enum class Page(val title: String, val icon: Int) {
    MENU("NoAmp Music", -1), ARTISTS("Artists", 0), ALBUMS("Albums", 1), SONGS("Songs", 2), FOLDERS("Folder", 3), PLAYLISTS("Playlists", 4), GENRES("Genre", 5)
}
private val ROW_A = listOf(Color(0xFF26292C), Color(0xFF2E3235))       // row backgrounds (left -> right), like the reference
private val ROW_B = listOf(Color(0xFF1B1E21), Color(0xFF222629))
private val ICON = Color(0xFFCDD0D4)
private val HEADER = listOf(Color(0xFF3B3E41), Color(0xFF4A4D51))

private fun dur(ms: Long): String { val s = (ms / 1000).toInt(); return "%d:%02d".format(s / 60, s % 60) }
private fun plural(n: Int, one: String) = "$n $one" + if (n == 1) "" else "s"

/**
 * Music library popup: Artists · Albums · Songs · Folder · Playlists · Genre.
 * It lists every audio file of the chosen music folders; tapping a song plays it with the rest of the list as the queue.
 */
@Composable
fun LibraryDialog(rig: Rig, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val t = rig.tools
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(Page.MENU) }
    var group by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var addTo by remember { mutableStateOf<Song?>(null) }          // song waiting to be put in a playlist
    var newPl by remember { mutableStateOf(false) }                 // "new playlist" name dialog
    var delPl by remember { mutableStateOf<Playlist?>(null) }
    LaunchedEffect(Unit) { MusicLibrary.loadPlaylists(ctx); MusicLibrary.loadFolders(ctx); if (!MusicLibrary.scanned) MusicLibrary.scan(ctx) }
    val granted = MusicLibrary.folders.isNotEmpty()
    fun addFolder() { chooseFolder("Choose a music folder")?.let { MusicLibrary.addFolder(ctx, it); scope.launch { MusicLibrary.scan(ctx) } } }
    fun back() { if (group != null) { group = null; query = "" } else if (page != Page.MENU) { page = Page.MENU; query = "" } else onClose() }
    fun rescan() { scope.launch { MusicLibrary.scan(ctx) } }

    val songs = MusicLibrary.songs
    val artists = remember(songs) { songs.groupBy { it.artist }.toSortedMap(String.CASE_INSENSITIVE_ORDER) }
    val albums = remember(songs) { songs.groupBy { it.album }.toSortedMap(String.CASE_INSENSITIVE_ORDER) }
    val folders = remember(songs) { songs.groupBy { it.folder }.toSortedMap(String.CASE_INSENSITIVE_ORDER) }
    val genres = remember(songs) { songs.groupBy { it.genre }.toSortedMap(String.CASE_INSENSITIVE_ORDER) }

    Dialog(onDismissRequest = { back() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Box(Modifier.fillMaxSize().background(ROW_B[0])) {
            Column(Modifier.fillMaxSize()) {
                if (page == Page.MENU) {
                    Box(Modifier.fillMaxWidth()) {
                        Image(asset("music_header.png"), contentDescription = "NoAmp Music", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                        Row(Modifier.align(Alignment.TopEnd).padding(6.dp)) { TopTxt("⟳") { rescan() }; TopTxt("✕") { onClose() } }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().background(Brush.horizontalGradient(HEADER)).padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TopTxt("←") { back() }
                        Text(group ?: page.title, color = C.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 6.dp))
                        TopTxt("⟳") { rescan() }; TopTxt("✕") { onClose() }
                    }
                }

                when {
                    !granted -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Choose the folder(s) with your music (MP3, WAV, FLAC) so NoAmp Music can scan them.", color = C.Text, fontSize = 14.sp)
                        Btn("Choose music folder…", C.Green) { addFolder() }
                    }
                    page == Page.MENU -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        if (MusicLibrary.scanning) Text("Scanning folders…", color = C.Amber, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                        else if (MusicLibrary.scanned && songs.isEmpty()) Text("No MP3 / WAV / FLAC files found in the chosen folders.", color = C.Amber, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            MusicLibrary.folders.toList().forEach { f ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(f, color = C.Dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    TopTxt("✕") { MusicLibrary.removeFolder(ctx, f); scope.launch { MusicLibrary.scan(ctx) } }
                                }
                            }
                            Btn("+ Add music folder", C.Accent) { addFolder() }
                        }
                        val counts = mapOf(Page.ARTISTS to artists.size, Page.ALBUMS to albums.size, Page.SONGS to songs.size, Page.FOLDERS to folders.size, Page.PLAYLISTS to MusicLibrary.playlists.size, Page.GENRES to genres.size)
                        Page.values().filter { it != Page.MENU }.forEachIndexed { i, p ->
                            Row(
                                Modifier.fillMaxWidth().height(78.dp).background(Brush.horizontalGradient(if (i % 2 == 0) ROW_A else ROW_B)).clickable { page = p; group = null; query = "" },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Spacer(Modifier.width(26.dp))
                                Canvas(Modifier.size(42.dp)) { drawLibIcon(p.icon) }
                                Spacer(Modifier.width(30.dp))
                                Text(p.title, color = Color(0xFFF2F4F6), fontSize = 23.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                Text("${counts[p] ?: 0}", color = C.Dim, fontSize = 13.sp, modifier = Modifier.padding(end = 22.dp))
                            }
                        }
                    }
                    else -> {
                        val g = group
                        val playlist = if (page == Page.PLAYLISTS && g != null) MusicLibrary.playlists.firstOrNull { it.name == g } else null
                        val list: List<Song>? = when {
                            page == Page.SONGS -> songs
                            g == null -> null
                            page == Page.ARTISTS -> artists[g]
                            page == Page.ALBUMS -> albums[g]
                            page == Page.FOLDERS -> folders[g]
                            page == Page.GENRES -> genres[g]
                            else -> playlist?.uris?.mapNotNull { MusicLibrary.find(it) }
                        }
                        if (list != null) {
                            val shown = if (query.isBlank()) list else list.filter { it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true) }
                            SearchField(query) { query = it }
                            if (g != null && shown.isNotEmpty()) Row(Modifier.padding(horizontal = 10.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Btn("▶ Play all", C.Green) { t.playSongs(shown, 0); onClose() }
                                Btn("Shuffle", C.Dim) { if (!t.shuffle) t.toggleShuffle(); t.playSongs(shown, Random.nextInt(shown.size)); onClose() }
                            }
                            if (shown.isEmpty()) Text(if (MusicLibrary.scanning) "Scanning storage…" else "No songs.", color = C.Dim, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                            LazyColumn(Modifier.fillMaxSize()) {
                                itemsIndexed(shown) { i, s ->
                                    SongRow(i, s, playlist != null, onPlay = { t.playSongs(shown, i); onClose() }, onQueue = { t.addToQueue(s) }, onAddPl = { addTo = s },
                                        onRemove = { playlist?.let { MusicLibrary.removeFromPlaylist(ctx, it, s.file.path) } })
                                }
                            }
                        } else when (page) {
                            Page.ARTISTS -> GroupList(artists.map { it.key to plural(it.value.size, "song") }, 0) { group = it }
                            Page.ALBUMS -> GroupList(albums.map { it.key to (it.value.first().artist + " · " + plural(it.value.size, "song")) }, 1) { group = it }
                            Page.FOLDERS -> GroupList(folders.map { it.key to plural(it.value.size, "song") }, 3) { group = it }
                            Page.GENRES -> GroupList(genres.map { it.key to plural(it.value.size, "song") }, 5) { group = it }
                            else -> LazyColumn(Modifier.fillMaxSize()) {
                                item { RowBox(0, onClick = { newPl = true }) { Text("＋  New playlist", color = C.Green, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 22.dp)) } }
                                itemsIndexed(MusicLibrary.playlists.toList()) { i, p ->
                                    RowBox(i + 1, onClick = { group = p.name }) {
                                        Canvas(Modifier.padding(start = 22.dp).size(30.dp)) { drawLibIcon(4) }
                                        Column(Modifier.weight(1f).padding(horizontal = 18.dp)) {
                                            Text(p.name, color = C.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(plural(p.uris.size, "song"), color = C.Dim, fontSize = 11.sp)
                                        }
                                        Text("✕", color = C.Dim, fontSize = 15.sp, modifier = Modifier.clickable { delPl = p }.padding(horizontal = 18.dp, vertical = 10.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- playlist dialogs
    if (newPl) NamePlaylistDialog(onCancel = { newPl = false; addTo = null }) { name ->
        val p = MusicLibrary.createPlaylist(ctx, name); addTo?.let { MusicLibrary.addToPlaylist(ctx, p, it) }; addTo = null; newPl = false
    }
    val song = addTo
    if (song != null && !newPl) AlertDialog(
        onDismissRequest = { addTo = null }, title = { Text("Add to playlist") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("＋  New playlist…", color = C.Green, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().clickable { newPl = true }.padding(vertical = 10.dp))
                MusicLibrary.playlists.toList().forEach { p ->
                    Text(p.name, color = C.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().clickable { MusicLibrary.addToPlaylist(ctx, p, song); addTo = null }.padding(vertical = 10.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { addTo = null }) { Text("Cancel") } }
    )
    delPl?.let { p ->
        AlertDialog(
            onDismissRequest = { delPl = null }, title = { Text("Delete playlist?") }, text = { Text("\"${p.name}\" will be deleted. The songs stay on your device.") },
            confirmButton = { TextButton(onClick = { MusicLibrary.deletePlaylist(ctx, p); delPl = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { delPl = null }) { Text("Cancel") } }
        )
    }
}

// ------------------------------------------------------------------------------------------------ pieces
@Composable
private fun TopTxt(text: String, onClick: () -> Unit) {
    Text(text, color = C.Text, fontSize = 22.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onClick() }.padding(horizontal = 14.dp, vertical = 6.dp))
}

@Composable
private fun RowBox(i: Int, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(68.dp).background(Brush.horizontalGradient(if (i % 2 == 0) ROW_A else ROW_B)).clickable { onClick() }, verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
private fun GroupList(items: List<Pair<String, String>>, icon: Int, onOpen: (String) -> Unit) {
    if (items.isEmpty()) { Text(if (MusicLibrary.scanning) "Scanning storage…" else "Nothing here yet.", color = C.Dim, fontSize = 13.sp, modifier = Modifier.padding(16.dp)); return }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(items) { i, (name, sub) ->
            RowBox(i, onClick = { onOpen(name) }) {
                Canvas(Modifier.padding(start = 22.dp).size(30.dp)) { drawLibIcon(icon) }
                Column(Modifier.weight(1f).padding(horizontal = 18.dp)) {
                    Text(name, color = C.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(sub, color = C.Dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun SongRow(i: Int, s: Song, inPlaylist: Boolean, onPlay: () -> Unit, onQueue: () -> Unit, onAddPl: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    RowBox(i, onClick = onPlay) {
        Column(Modifier.weight(1f).padding(start = 22.dp, end = 8.dp)) {
            Text(s.title, color = C.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.artist + " · " + s.album, color = C.Dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (s.durMs > 0) Text(dur(s.durMs), color = C.Dim, fontSize = 12.sp, fontFamily = Mono)
        Box {
            Text("⋮", color = C.Text, fontSize = 20.sp, modifier = Modifier.clickable { menu = true }.padding(horizontal = 16.dp, vertical = 10.dp))
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Add to queue") }, onClick = { menu = false; onQueue() })
                DropdownMenuItem(text = { Text("Add to playlist…") }, onClick = { menu = false; onAddPl() })
                if (inPlaylist) DropdownMenuItem(text = { Text("Remove from playlist") }, onClick = { menu = false; onRemove() })
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2C3034)).padding(horizontal = 14.dp, vertical = 11.dp)) {
        if (value.isEmpty()) Text("Search", color = C.Dim, fontSize = 14.sp)
        BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(color = C.Text, fontSize = 14.sp), cursorBrush = SolidColor(C.Green), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun NamePlaylistDialog(onCancel: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel, title = { Text("New playlist") },
        text = {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF2C3034)).padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (name.isEmpty()) Text("Playlist name", color = C.Dim, fontSize = 14.sp)
                BasicTextField(name, { name = it }, singleLine = true, textStyle = TextStyle(color = C.Text, fontSize = 15.sp), cursorBrush = SolidColor(C.Green), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

/** 0 artist · 1 album · 2 song · 3 folder · 4 playlist · 5 genre (guitar) */
private fun DrawScope.drawLibIcon(kind: Int) {
    val w = size.width; val dark = Color(0xFF1E2124); val c = ICON; val sw = w * 0.07f
    when (kind) {
        0 -> {
            drawCircle(c, w * .21f, Offset(w * .5f, w * .27f))
            drawPath(Path().apply { moveTo(w * .10f, w * .96f); quadraticBezierTo(w * .10f, w * .56f, w * .5f, w * .56f); quadraticBezierTo(w * .90f, w * .56f, w * .90f, w * .96f); close() }, c)
        }
        1 -> { drawCircle(c, w * .48f, center); drawCircle(dark, w * .22f, center); drawCircle(c, w * .13f, center); drawCircle(dark, w * .06f, center) }
        2 -> {
            drawOval(c, Offset(w * .08f, w * .66f), Size(w * .30f, w * .22f)); drawOval(c, Offset(w * .56f, w * .58f), Size(w * .30f, w * .22f))
            drawLine(c, Offset(w * .35f, w * .76f), Offset(w * .35f, w * .16f), sw); drawLine(c, Offset(w * .83f, w * .68f), Offset(w * .83f, w * .08f), sw)
            drawLine(c, Offset(w * .35f, w * .16f), Offset(w * .83f, w * .08f), sw * 1.6f)
        }
        3 -> {
            drawRoundRect(c, Offset(w * .04f, w * .14f), Size(w * .42f, w * .22f), CornerRadius(w * .04f))
            drawRoundRect(c, Offset(w * .04f, w * .26f), Size(w * .92f, w * .62f), CornerRadius(w * .05f))
        }
        4 -> {
            for (k in 0..3) drawLine(c, Offset(w * .04f, w * (.18f + k * .20f)), Offset(w * (if (k == 3) .40f else .56f), w * (.18f + k * .20f)), sw, StrokeCap.Round)
            drawOval(c, Offset(w * .56f, w * .70f), Size(w * .28f, w * .20f)); drawLine(c, Offset(w * .82f, w * .80f), Offset(w * .82f, w * .12f), sw)
            drawLine(c, Offset(w * .82f, w * .12f), Offset(w * .98f, w * .30f), sw, StrokeCap.Round)
        }
        else -> {
            drawLine(c, Offset(w * .56f, w * .44f), Offset(w * .90f, w * .10f), sw * 1.1f, StrokeCap.Round)
            drawRoundRect(c, Offset(w * .80f, w * .02f), Size(w * .16f, w * .16f), CornerRadius(w * .03f))
            drawCircle(c, w * .17f, Offset(w * .48f, w * .46f)); drawCircle(c, w * .23f, Offset(w * .32f, w * .68f))
            drawCircle(dark, w * .06f, Offset(w * .40f, w * .58f))
        }
    }
}
