package com.noamp.fx

import org.json.JSONObject
import java.io.File

/**
 * NAM files written by recent trainers (v0.7+) can be a "SlimmableContainer": one file that holds several WaveNet
 * sub-models of different size. The engine loads plain WaveNet models, so for a container we pick one sub-model
 * (Lite = smallest, Full = largest) and write it to a temporary standalone .nam file.
 */
object NamFile {
    class Prepared(val path: String, val slim: Boolean)

    fun prepare(src: File, cacheDir: File, quality: Int): Prepared {
        if (!src.exists() || src.length() > (24L shl 20)) return Prepared(src.absolutePath, false)
        return try {
            val root = JSONObject(src.readText())
            if (root.optString("architecture") != "SlimmableContainer") return Prepared(src.absolutePath, false)
            val subs = root.getJSONObject("config").getJSONArray("submodels")
            val list = (0 until subs.length()).map { subs.getJSONObject(it) }.sortedBy { it.optDouble("max_value", 1.0) }
            if (list.isEmpty()) return Prepared(src.absolutePath, false)
            val lite = quality <= 0
            val model = (if (lite) list.first() else list.last()).getJSONObject("model")
            if (!model.has("metadata") && root.has("metadata")) model.put("metadata", root.getJSONObject("metadata"))
            cacheDir.mkdirs()
            val out = File(cacheDir, "slim_" + src.nameWithoutExtension + (if (lite) "_lite" else "_full") + ".nam")
            out.writeText(model.toString())
            Prepared(out.absolutePath, true)
        } catch (_: Exception) {
            Prepared(src.absolutePath, false)
        }
    }

    /** "High_Gain.nam" -> "High Gain" */
    fun label(fileName: String) = fileName.removeSuffix(".nam").replace('_', ' ')
}
