package com.noamp.fx.ui

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import com.noamp.fx.Context
import com.noamp.fx.appContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** Desktop stand-ins for the Android Compose helpers the shared screens used. */
val LocalContext = staticCompositionLocalOf<Context> { appContext }

/** There is no View on the desktop: Haptics ignores it. */
val LocalView = staticCompositionLocalOf<Any?> { null }

fun ImageBitmap.asImageBitmap(): ImageBitmap = this

/** Image from src/main/resources (e.g. "logo_banner.png"), decoded once. */
@Composable
fun asset(name: String): Painter = remember(name) { BitmapPainter(useResource(name) { loadImageBitmap(it) }) }

/**
 * "Back" on the desktop = the Esc key. The innermost enabled handler runs first (see Main.kt, which forwards Esc to [BackStack.fire]).
 */
object BackStack {
    private val stack = ArrayList<() -> Unit>()
    fun push(f: () -> Unit) { synchronized(stack) { stack.add(f) } }
    fun remove(f: () -> Unit) { synchronized(stack) { stack.remove(f) } }
    fun fire(): Boolean { val f = synchronized(stack) { stack.lastOrNull() } ?: return false; f(); return true }
}

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val cb by rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        val f: () -> Unit = { cb() }
        if (enabled) BackStack.push(f)
        onDispose { BackStack.remove(f) }
    }
}

// ---------------------------------------------------------------- native Windows file dialogs
private fun showDialog(title: String, mode: Int, exts: List<String>, defaultName: String? = null, multi: Boolean = false): List<File> {
    val fd = FileDialog(null as Frame?, title, mode)
    if (multi) fd.isMultipleMode = true
    if (defaultName != null) fd.file = defaultName
    else if (exts.isNotEmpty()) fd.file = exts.joinToString(";") { "*.$it" }          // the Windows dialog uses this as its file filter
    fd.isVisible = true
    val dir = fd.directory ?: return emptyList()
    val files = fd.files?.toList() ?: listOfNotNull(fd.file?.let { File(dir, it) })
    return files.filter { it.name.isNotEmpty() }
}

/** Returns a function that opens a "choose file" dialog; [onResult] gets the chosen file (never null). */
@Composable
fun rememberOpenFile(title: String, vararg exts: String, onResult: (File) -> Unit): () -> Unit {
    val cb by rememberUpdatedState(onResult)
    return remember(title) { { showDialog(title, FileDialog.LOAD, exts.toList()).firstOrNull()?.let { cb(it) }; Unit } }
}

@Composable
fun rememberOpenFiles(title: String, vararg exts: String, onResult: (List<File>) -> Unit): () -> Unit {
    val cb by rememberUpdatedState(onResult)
    return remember(title) { { showDialog(title, FileDialog.LOAD, exts.toList(), multi = true).let { if (it.isNotEmpty()) cb(it) }; Unit } }
}

/** Returns a function taking the suggested file name; opens a "save as" dialog. */
@Composable
fun rememberSaveFile(title: String, ext: String, onResult: (File) -> Unit): (String) -> Unit {
    val cb by rememberUpdatedState(onResult)
    return remember(title) { { name: String ->
        showDialog(title, FileDialog.SAVE, listOf(ext), defaultName = name).firstOrNull()?.let { f ->
            cb(if (f.extension.isEmpty()) File(f.path + "." + ext) else f)
        }; Unit
    } }
}

/** Folder chooser (Windows: the AWT dialog picks a folder when the "apple.awt.fileDialogForDirectories" trick is not available, so a Swing chooser is used). */
fun chooseFolder(title: String): File? {
    val ch = javax.swing.JFileChooser().apply { dialogTitle = title; fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY }
    return if (ch.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) ch.selectedFile else null
}
