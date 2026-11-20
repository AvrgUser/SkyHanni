package at.hannibal2.skyhanni.api.event

import at.hannibal2.skyhanni.SkyHanniMod
import at.hannibal2.skyhanni.config.commands.CommandRegistrationEvent
import at.hannibal2.skyhanni.events.DebugDataCollectEvent
import at.hannibal2.skyhanni.skyhannimodule.SkyHanniModule
import at.hannibal2.skyhanni.utils.ChatUtils
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.LongAccumulator
import java.util.concurrent.atomic.LongAdder

/**
 * Measures how long posting each event takes, to compare event system implementations.
 *
 * [SkyHanniEvent] calls [record] around the whole handler post, so it works the same for any implementation.
 * Times are inclusive: an event posted from inside a listener is counted in its own entry and in the outer one.
 */
@SkyHanniModule(devOnly = true)
object ProfileEvents {

    private const val ENABLE_PROPERTY = "skyhanni.eventProfile"
    private const val LABEL_PROPERTY = "skyhanni.eventProfile.label"
    private const val DIR_PROPERTY = "skyhanni.eventProfile.dir"
    private val DEFAULT_DIR = SkyHanniMod.dataDir.toPath().resolve("event-profiles")
    private const val BUCKETS = 64
    private const val DEBUG_SHOWN = 15

    @JvmField
    @Volatile
    var enabled = false

    private val stats = ConcurrentHashMap<Class<out SkyHanniEvent>, EventStats>()
    private val shutdownHook by lazy { Thread(::dumpQuietly, "SkyHanni event profile dump") }
    private var hookInstalled = false

    class EventStats {
        val count = LongAdder()
        val totalNanos = LongAdder()
        val maxNanos = LongAccumulator({ a, b -> maxOf(a, b) }, 0L)
        val histogram = AtomicLongArray(BUCKETS)
        val threads: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** Upper bound of the bucket containing the [quantile], in nanoseconds. */
        fun percentile(quantile: Double): Long {
            val total = count.sum()
            if (total == 0L) return 0
            val target = (total * quantile).toLong().coerceIn(1, total)
            var seen = 0L
            for (bucket in 0 until BUCKETS) {
                seen += histogram.get(bucket)
                if (seen >= target) return if (bucket >= 62) Long.MAX_VALUE else 1L shl (bucket + 1)
            }
            return maxNanos.get()
        }
    }

    init {
        if (System.getProperty(ENABLE_PROPERTY) != null) start()
    }

    @Synchronized
    fun start() {
        stats.clear()
        enabled = true
        if (!hookInstalled) {
            Runtime.getRuntime().addShutdownHook(shutdownHook)
            hookInstalled = true
        }
    }

    fun stop() {
        enabled = false
    }

    fun reset() = stats.clear()

    /** Called by [SkyHanniEvent] after posting an event while [enabled]. */
    @JvmStatic
    fun record(eventClass: Class<out SkyHanniEvent>, nanos: Long) {
        val entry = stats[eventClass] ?: stats.computeIfAbsent(eventClass) { EventStats() }
        entry.count.increment()
        entry.totalNanos.add(nanos)
        entry.maxNanos.accumulate(nanos)
        entry.histogram.incrementAndGet(63 - java.lang.Long.numberOfLeadingZeros(nanos.coerceAtLeast(1)))
        entry.threads.add(Thread.currentThread().name)
    }

    /** Event classes are the keys, the printed name is only derived here (nested and sealed classes stay distinct). */
    private fun nameOf(eventClass: Class<*>): String =
        eventClass.name.substringAfterLast('.').replace('$', '.')

    private fun report(): String = buildString {
        appendLine("event\tcount\ttotal_ns\tmean_ns\tp50_ns\tp99_ns\tmax_ns\tthreads")
        stats.entries
            .sortedByDescending { it.value.totalNanos.sum() }
            .forEach { (eventClass, s) ->
                val count = s.count.sum()
                val total = s.totalNanos.sum()
                append(nameOf(eventClass)).append('\t')
                append(count).append('\t')
                append(total).append('\t')
                append(if (count == 0L) 0 else total / count).append('\t')
                append(s.percentile(0.5)).append('\t')
                append(s.percentile(0.99)).append('\t')
                append(s.maxNanos.get()).append('\t')
                appendLine(s.threads.sorted().joinToString(","))
            }
    }

    fun dump(): Path {
        val dir = System.getProperty(DIR_PROPERTY)?.let { Path.of(it) } ?: DEFAULT_DIR
        Files.createDirectories(dir)
        val file = dir.resolve("${System.getProperty(LABEL_PROPERTY) ?: "run-${System.currentTimeMillis()}"}.tsv")
        Files.writeString(file, report())
        return file
    }

    private fun dumpQuietly() {
        try {
            dump()
        } catch (_: Exception) {
            // shutting down, nothing sensible left to do
        }
    }

    @HandleEvent
    private fun onDebugDataCollect(event: DebugDataCollectEvent) {
        event.title("Event profile")
        event.addIrrelevant {
            if (!enabled) {
                add("Disabled. Start with -D$ENABLE_PROPERTY or ProfileEvents.start().")
                return@addIrrelevant
            }
            add("- <event> <count> <mean us> <p99 us> <max us>")
            stats.entries
                .sortedByDescending { it.value.totalNanos.sum() }
                .take(DEBUG_SHOWN)
                .forEach { (eventClass, s) ->
                    val count = s.count.sum()
                    val mean = if (count == 0L) 0.0 else s.totalNanos.sum() / count / 1000.0
                    add(
                        "- ${nameOf(eventClass)} $count %.1f %.1f %.1f"
                            .format(mean, s.percentile(0.99) / 1000.0, s.maxNanos.get() / 1000.0),
                    )
                }
        }
    }

    @HandleEvent
    private fun onCommandRegister(event: CommandRegistrationEvent) {
        event.registerBrigadier("shtestprofileevents") {
            description = "Profile event system performance"
            category = DEVELOPER_DEBUG
            literalCallback("start") {
                ChatUtils.chat("Event profiling starting.")
                start()
            }
            literalCallback("stop") {
                ChatUtils.chat("Event profiling stopping.")
                stop()
            }
            literalCallback("reset") {
                ChatUtils.chat("Event profiling reset.")
                reset()
            }
            literalCallback("dump") {
                val file = dump()
                ChatUtils.clickableLinkChat(
                    "Event profiling dumped to ${file.fileName}",
                    url = file.toString(),
                    openPath = true,
                )
            }
            literalCallback("report") {
                ChatUtils.chat("Event profiling report:\n${report().replace("\t", " | ")}")
            }
            literalCallback("status") {
                ChatUtils.chat("Event profiling is ${if (enabled) "enabled" else "disabled"}.")
            }
        }
    }
}
