package unicode.sinhala.keyboard.ai

import android.content.Context
import android.os.SystemClock

/** Local, conservative request-attempt counter. Never reports Google's actual remaining quota. */
internal object FoxGeminiUsage {
    const val PRIMARY = "Google Gemini"
    fun slotName(slot: Int) = if (slot == 0) PRIMARY else "Google Gemini Backup ${slot + 1}"
    data class Snapshot(val minute: Int, val daily: Int, val perKey: List<Int>, val successes: List<Int>, val blockedMs: Long)
    private var blockedUntil = 0L
    private fun prefs(c: Context) = c.getSharedPreferences("fox_gemini_usage_v9", Context.MODE_PRIVATE)
    private fun today() = FoxSmartRules.pacificDay(System.currentTimeMillis())
    private fun day(c: Context): String = today()
    private fun recent(c: Context, now: Long): List<Long> = prefs(c).getString("minute", "").orEmpty()
        .split(',').mapNotNull { it.toLongOrNull() }.filter { it <= now && now - it < 60000L }
    @Synchronized fun snapshot(c: Context): Snapshot {
        val p = prefs(c); val d = day(c)
        val used = (0..2).map { p.getInt("$d.attempt.$it", 0) }
        return Snapshot(recent(c, System.currentTimeMillis()).size, used.sum(), used,
            (0..2).map { p.getInt("$d.success.$it", 0) },
            (blockedUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0))
    }
    @Synchronized fun reserve(c: Context, slot: Int, optional: Boolean = false): Boolean {
        val s = snapshot(c)
        if (s.blockedMs > 0 || s.minute >= FoxSmartRules.RPM || s.daily >= FoxSmartRules.RPD) return false
        // Preserve headroom for interactive taps, never use a second credential to evade limits.
        if (optional && (s.minute >= 9 || s.daily >= 400)) return false
        val p = prefs(c); val d = day(c); val now = System.currentTimeMillis()
        val count = p.getInt("$d.attempt.$slot", 0)
        val e = p.edit().putInt("$d.attempt.$slot", count + 1)
            .putString("minute", (recent(c, now) + now).joinToString(","))
        // Remove old day counters, keep today's counts and minute history.
        for (k in p.all.keys) if (k != "minute" && !k.startsWith("$d.")) e.remove(k)
        e.apply()
        return true
    }
    @Synchronized fun success(c: Context, slot: Int) {
        val p = prefs(c); val d = day(c); val k = "$d.success.$slot"
        p.edit().putInt(k, p.getInt(k, 0) + 1).apply()
    }
    @Synchronized fun block(ms: Long) { blockedUntil = maxOf(blockedUntil, SystemClock.elapsedRealtime() + ms) }
}
