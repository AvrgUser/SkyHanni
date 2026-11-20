package at.hannibal2.skyhanni.api.event.kinds

import at.hannibal2.skyhanni.api.event.EventListeners.Listener
import at.hannibal2.skyhanni.api.event.EventPredicate
import at.hannibal2.skyhanni.api.event.SkyHanniEvent

/**
 * Events implementing [SkyHanniEvent.Cancellable].
 *
 * - If no active listener wants canceled events, nothing after a cancel can run, so the whole post stops at the
 *   cancel and listeners need no check of their own.
 * - Otherwise every listener that does not want canceled events checks for itself.
 */
object CancellableKind : EventKind {

    private val IS_CANCELLED: EventPredicate = { event -> event.isCancelled }
    private val NOT_CANCELLED: EventPredicate = { event -> !event.isCancelled }

    override fun appliesTo(eventClass: Class<*>): Boolean =
        SkyHanniEvent.Cancellable::class.java.isAssignableFrom(eventClass)

    override fun plan(active: List<Listener>): DispatchPlan =
        if (active.none { it.receiveCancelled }) {
            DispatchPlan(stopWhen = IS_CANCELLED)
        } else {
            DispatchPlan(listenerFilter = { listener -> if (listener.receiveCancelled) null else NOT_CANCELLED })
        }
}
