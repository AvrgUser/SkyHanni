package at.hannibal2.skyhanni.api.event.kinds

import at.hannibal2.skyhanni.api.event.EventListeners.Listener
import at.hannibal2.skyhanni.api.event.GenericSkyHanniEvent

/**
 * Events extending [GenericSkyHanniEvent]: a listener only runs if the type it declared matches the event's type.
 */
object GenericKind : EventKind {

    override fun appliesTo(eventClass: Class<*>): Boolean =
        GenericSkyHanniEvent::class.java.isAssignableFrom(eventClass)

    override fun plan(active: List<Listener>): DispatchPlan = DispatchPlan(
        listenerFilter = { listener ->
            val generic = listener.generic
            if (generic == null) {
                null
            } else {
                { event -> event is GenericSkyHanniEvent<*> && generic.isAssignableFrom(event.type) }
            }
        },
    )
}
