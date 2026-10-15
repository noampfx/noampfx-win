package com.noamp.fx

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.Properties
import java.util.Timer
import java.util.TimerTask

/** Where all user data lives: %APPDATA%\NoAmpFX (presets, models, IRs, recordings, settings). */
object AppDirs {
    val root: File = run {
        val base = System.getenv("APPDATA") ?: (System.getProperty("user.home") + File.separator + ".config")
        File(base, "NoAmpFX").apply { mkdirs() }
    }
}

/**
 * Stand-in for android.content.SharedPreferences: key/value settings kept in one .properties file per name.
 * Writes are batched (a slider drag does not hit the disk on every move) and flushed on exit.
 */
class Prefs private constructor(private val file: File) {
    private val props = Properties()
    private var timer: TimerTask? = null

    init { if (file.exists()) try { file.reader(Charsets.UTF_8).use { props.load(it) } } catch (_: Exception) { } }

    @Synchronized fun getString(k: String, d: String?): String? = props.getProperty(k) ?: d
    @Synchronized fun getInt(k: String, d: Int): Int = props.getProperty(k)?.toIntOrNull() ?: d
    @Synchronized fun getLong(k: String, d: Long): Long = props.getProperty(k)?.toLongOrNull() ?: d
    @Synchronized fun getFloat(k: String, d: Float): Float = props.getProperty(k)?.toFloatOrNull() ?: d
    @Synchronized fun getBoolean(k: String, d: Boolean): Boolean = props.getProperty(k)?.toBooleanStrictOrNull() ?: d
    @Synchronized fun contains(k: String): Boolean = props.containsKey(k)

    fun edit() = Editor()

    inner class Editor {
        private val ops = ArrayList<(Properties) -> Unit>()
        fun putString(k: String, v: String?): Editor { ops.add { if (v == null) it.remove(k) else it.setProperty(k, v) }; return this }
        fun putInt(k: String, v: Int): Editor { ops.add { it.setProperty(k, v.toString()) }; return this }
        fun putLong(k: String, v: Long): Editor { ops.add { it.setProperty(k, v.toString()) }; return this }
        fun putFloat(k: String, v: Float): Editor { ops.add { it.setProperty(k, v.toString()) }; return this }
        fun putBoolean(k: String, v: Boolean): Editor { ops.add { it.setProperty(k, v.toString()) }; return this }
        fun remove(k: String): Editor { ops.add { it.remove(k) }; return this }
        fun apply() { synchronized(this@Prefs) { ops.forEach { it(props) } }; schedule() }
        fun commit(): Boolean { apply(); flush(); return true }
    }

    @Synchronized private fun schedule() {
        if (timer != null) return
        timer = object : TimerTask() { override fun run() { flush() } }
        SAVER.schedule(timer, 400)
    }

    @Synchronized fun flush() {
        timer?.cancel(); timer = null
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writer(Charsets.UTF_8).use { props.store(it, null) }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (_: Exception) { }
    }

    companion object {
        private val SAVER = Timer("noamp-prefs", true)
        private val all = HashMap<String, Prefs>()
        init { Runtime.getRuntime().addShutdownHook(Thread { synchronized(all) { all.values.forEach { it.flush() } } }) }
        fun of(name: String): Prefs = synchronized(all) { all.getOrPut(name) { Prefs(File(AppDirs.root, "$name.properties")) } }
    }
}

/** Built-in files shipped inside the app (the .nam models), listed by an index.txt next to them. */
object Assets {
    fun list(dir: String): Array<String>? =
        Assets::class.java.getResourceAsStream("/$dir/index.txt")?.bufferedReader()?.use { r -> r.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray() }
    fun open(path: String): InputStream = Assets::class.java.getResourceAsStream("/$path") ?: throw FileNotFoundException(path)
}

/** Minimal replacement for android.content.Context: only the folders / preferences / assets the shared code asks for. */
class Context {
    val filesDir: File get() = AppDirs.root
    val cacheDir: File = File(AppDirs.root, "cache").apply { mkdirs() }
    val applicationContext: Context get() = this
    val packageName: String = "com.noamp.fx"
    val assets = Assets
    fun getSharedPreferences(name: String, @Suppress("UNUSED_PARAMETER") mode: Int = 0): Prefs = Prefs.of(name)
    companion object { const val MODE_PRIVATE = 0 }
}
typealias Application = Context
typealias SharedPreferences = Prefs

val appContext: Context by lazy { Context() }

object Clip {
    fun copy(text: String) { try { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) } catch (_: Throwable) { } }
    fun paste(): String = try { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String ?: "" } catch (_: Throwable) { "" }
}

/** Opens a file or folder with the Windows default application / Explorer. */
fun openInShell(f: File) { try { java.awt.Desktop.getDesktop().open(f) } catch (_: Throwable) { } }
