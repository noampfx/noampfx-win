package com.noamp.fx

/** Kept so the shared pedal UI compiles unchanged. A desktop has no vibration motor, so these do nothing. */
object Haptics {
    @Volatile var on = false
    fun tick(@Suppress("UNUSED_PARAMETER") v: Any?) { }
    fun heavy(@Suppress("UNUSED_PARAMETER") v: Any?) { }
}
