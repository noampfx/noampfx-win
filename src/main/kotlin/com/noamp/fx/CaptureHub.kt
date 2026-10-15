package com.noamp.fx

/** "Full Record" (mixing the audio of other apps into a recording) is an Android-only feature; this stub keeps the recorder code unchanged. */
object CaptureHub {
    val running: Boolean get() = false
    var collecting = false
    fun reset() { }
    fun read(@Suppress("UNUSED_PARAMETER") out: FloatArray, @Suppress("UNUSED_PARAMETER") n: Int): Int = 0
}
