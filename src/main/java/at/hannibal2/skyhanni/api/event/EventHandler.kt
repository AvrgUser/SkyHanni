package at.hannibal2.skyhanni.api.event

import at.hannibal2.skyhanni.SkyHanniMod
import at.hannibal2.skyhanni.api.event.EventListeners.Listener
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
    private val canReceiveCancelled: Boolean,
    private val predicates: List<(T) -> Boolean>,
) {

    constructor(event: Class<T>, listeners: List<Listener>) : this(
        (event.name.split(".").lastOrNull() ?: event.name).replace("$", "."),
        ListenerCollection(listeners),
        getPredicates(event, listeners),
    )

    private class Snapshot(val generation: Int, val listeners: Array<Listener>)


    @Volatile
    private var snapshot = Snapshot(-1, NO_LISTENERS)

    private fun currentSnapshot(): Snapshot {
        val generation = SkyHanniEvents.getListenerCacheGeneration()
        val current = snapshot
        if (current.generation == generation) return current

        val state = SkyHanniEvents.getCurrentStateIndex()

        // Rare path: island change, repo reload, feature toggle, first post.
        val listeners = if (SkyHanniEvents.isDisabledHandler(name)) {
            NO_LISTENERS
        } else {
            listenerCollection.forState(state)
                .filter { it.passesStaticChecks() }
                .toTypedArray()
        }
        return Snapshot(generation, listeners).also { snapshot = it }
    }

    fun post(event: T, onError: ((Throwable) -> Unit)? = null) {
        val listeners = currentSnapshot().listeners
        if (listeners.isEmpty()) return

        var errors = 0
        for (listener in listeners) {
            if (listener.needsEventCheck && !listener.matches(event)) continue

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
        val NO_LISTENERS = emptyArray<Listener>()


        private fun <T: SkyHanniEvent> getPredicates(event: Class<T>, listeners: List<Listener>): List<(T) -> Boolean> = buildList {
            if (listeners.any { it.receiveCancelled }) {
                add { event -> event.isCancelled }
            }
            if (GenericSkyHanniEvent::class.java.isAssignableFrom(event)) {
                add { event -> (event as GenericSkyHanniEvent<*>).type }
            }
        }
    }
}
