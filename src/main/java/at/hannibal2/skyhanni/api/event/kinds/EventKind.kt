package at.hannibal2.skyhanni.api.event.kinds

import at.hannibal2.skyhanni.api.event.EventListeners.Listener
import at.hannibal2.skyhanni.api.event.EventPredicate

/**
 * A capability of an event class (cancellable, generic, ...) and the way it changes how listeners are called.
 *
 * Plans are made when a handler's listener snapshot is rebuilt (island change, repo reload, first post),
 * never while posting, so kinds can look at all listeners that are active right now.
 *
 * To add a kind: implement this interface, and add the object to [EventKinds.all].
 */
interface EventKind {

    /** Whether events of [eventClass] have this kind. Evaluated once per handler. */
    fun appliesTo(eventClass: Class<*>): Boolean

    /** How this kind shapes dispatch, given the [active] listeners, which are sorted by priority. */
    fun plan(active: List<Listener>): DispatchPlan
}

/**
 * What an [EventKind] asks of the handler.
 */
class DispatchPlan(
    /**
     * Stop calling listeners as soon as this is true for the event. Checked before the first listener and after
     * each one.
     */
    val stopWhen: EventPredicate? = null,
    /**
     * A predicate a listener must satisfy to be called, or null if this kind never filters that listener.
     * Called once per listener when the snapshot is built, the returned predicate is what runs per post.
     */
    val listenerFilter: ((Listener) -> EventPredicate?)? = null,
) {
    companion object {
        val NONE = DispatchPlan()
    }
}

object EventKinds {

    /** Every kind. A handler uses the ones that apply to its event class. */
    val all: List<EventKind> = listOf(CancellableKind, GenericKind)
}
