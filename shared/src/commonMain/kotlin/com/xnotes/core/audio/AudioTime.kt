package com.xnotes.core.audio

/** Times as the audio controls show them. */
object AudioTime {

    /** [ms] as minutes and seconds ("03:12"), with hours in front when [long] ("1:03:12"). */
    fun format(ms: Int, long: Boolean = ms >= 3_600_000): String {
        val s = (if (ms < 0) 0 else ms) / 1000
        val sec = s % 60
        val min = s / 60 % 60
        val hours = s / 3600
        fun two(v: Int) = if (v < 10) "0$v" else "$v"
        return if (long || hours > 0) "$hours:${two(min)}:${two(sec)}" else "${two(s / 60)}:${two(sec)}"
    }
}
