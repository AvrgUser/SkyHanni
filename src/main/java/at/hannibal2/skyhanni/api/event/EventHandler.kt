package at.hannibal2.skyhanni.api.event

import at.hannibal2.skyhanni.SkyHanniMod
import at.hannibal2.skyhanni.api.event.EventListeners.Listener
import at.hannibal2.skyhanni.api.event.kinds.EventKind
import at.hannibal2.skyhanni.api.event.kinds.EventKinds
import at.hannibal2.skyhanni.test.command.ErrorManager
import at.hannibal2.skyhanni.test.command.ErrorManager.maybeSkipError
import at.hannibal2.skyhanni.utils.ChatUtils
import at.hannibal2.skyhanni.utils.StringUtils
import at.hannibal2.skyhanni.utils.compat.componentBuilder
import at.hannibal2.skyhanni.utils.compat.withColor
import net.minecraft.ChatFormatting

class EventHandler<T : SkyHanniEvent> private constructor(
    val name: String,
    private val listenerCollection: ListenerCollection,
    private val kinds: List<EventKind>,
) {

    constructor(event: Class<T>, listeners: List<Listener>) : this(
        (event.name.split(".").lastOrNull() ?: event.name).replace("$", "."),
        ListenerCollection(listeners),
        EventKinds.all.filter { it.appliesTo(event) },
    )

    /**
     * A listener together with every check of this handler that looks at the event
     */
    private class Entry(val listener: Listener, val predicates: Array<EventPredicate>) {
        fun accepts(event: SkyHanniEvent): Boolean {
            for (predicate in predicates) {
                if (!predicate(event)) return false
            }
            return true
        }
    }

    private class Snapshot(
        val generation: Int = -1,
        val entries: Array<Entry> = NO_ENTRIES,
        val stopWhen: EventPredicate? = null,
    )

    @Volatile
    private var snapshot = Snapshot()

    private fun currentSnapshot(): Snapshot {
        val generation = SkyHanniEvents.getListenerCacheGeneration()
        val current = snapshot
        if (current.generation == generation) return current
        val state = SkyHanniEvents.getCurrentStateIndex()

        // Rare path: island change, repo reload, feature toggle, first post.
        return buildSnapshot(generation, state).also { snapshot = it }
    }

    private fun buildSnapshot(generation: Int, state: Int): Snapshot {
        if (SkyHanniEvents.isDisabledHandler(name)) return Snapshot(generation, NO_ENTRIES, null)

        val active = listenerCollection.forState(state).filter { it.passesStaticChecks() }
        val plans = kinds.map { it.plan(active) }

        val entries = Array(active.size) { index ->
            val listener = active[index]
            val predicates = buildList {
                for (plan in plans) plan.listenerFilter?.invoke(listener)?.let(::add)
            }
            Entry(listener, predicates.toTypedArray())
        }
        return Snapshot(generation, entries, mergeStops(plans.mapNotNull { it.stopWhen }))
    }

    private fun mergeStops(stops: List<EventPredicate>): EventPredicate? = when (stops.size) {
        0 -> null
        1 -> stops[0]
        else -> { event -> stops.any { it(event) } }
    }

    fun post(event: T, onError: ((Throwable) -> Unit)? = null) {
        val snapshot = currentSnapshot()
        val entries = snapshot.entries
        if (entries.isEmpty()) return

        val stopWhen = snapshot.stopWhen
        if (stopWhen != null && stopWhen(event)) return

        var errors = 0
        for (entry in entries) {
            if (!entry.accepts(event)) continue

            val listener = entry.listener
            try {
                listener.invoker.accept(event)
            } catch (originalThrowable: Throwable) {
                val throwable = originalThrowable.maybeSkipError()
                errors++
                if (errors <= MAX_SHOWN_ERRORS) {
                    val errorName = throwable::class.simpleName ?: "error"
                    val aOrAn = StringUtils.optionalAn(errorName)
                    val message = "Caught $aOrAn $errorName in ${listener.name} at $name: ${throwable.message}"
                    ErrorManager.logErrorWithData(throwable, message, ignoreErrorCache = onError != null)
                }
                onError?.invoke(throwable)
            }

            if (stopWhen != null && stopWhen(event)) break
        }

        if (errors > MAX_SHOWN_ERRORS) {
            val hiddenErrors = errors - MAX_SHOWN_ERRORS
            ChatUtils.chat(
                componentBuilder {
                    append("[SkyHanni/${SkyHanniMod.VERSION}] $hiddenErrors more errors in $name are hidden!")
                    withColor(ChatFormatting.RED)
                },
            )
        }
    }

    private companion object {
        const val MAX_SHOWN_ERRORS = 3
        val NO_ENTRIES = emptyArray<Entry>()
    }
}
