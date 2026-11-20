package at.hannibal2.skyhanni.api.event

import at.hannibal2.skyhanni.config.enums.OutsideSBFeature
import at.hannibal2.skyhanni.utils.ReflectionUtils
import at.hannibal2.skyhanni.utils.SkyBlockUtils
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

class EventListeners private constructor(val name: String, private val isGeneric: Boolean) {

    private val listeners = CopyOnWriteArrayList<Listener>()

    constructor(event: Class<*>) : this(
        (event.name.split(".").lastOrNull() ?: event.name).replace("$", "."),
        GenericSkyHanniEvent::class.java.isAssignableFrom(event),
    )

    fun removeListener(method: Method, instance: Any) {
        listeners.removeIf { it.method == method && it.instance === instance }
    }

    fun addListener(method: Method, instance: Any, options: HandleEvent) {
        val name = buildListenerName(method)
        val eventConsumer: Consumer<Any> = when (method.parameterCount) {
            0 -> ReflectionUtils.createNoParameterConsumerFromMethod(instance, method)
            1 -> ReflectionUtils.createConsumerFromMethod(instance, method)
            else -> throw IllegalArgumentException(
                "Method ${method.name} must have either 0 or 1 parameters.",
            )
        }
        val generic = if (isGeneric) resolveGenericType(method) else null

        listeners.add(Listener(name, eventConsumer, options, generic, method = method, instance = instance))
    }

    private fun buildListenerName(method: Method): String {
        val paramTypesString = method.parameterTypes.joinTo(
            StringBuilder(),
            prefix = "(",
            postfix = ")",
            separator = ", ",
            transform = Class<*>::getTypeName,
        ).toString()

        return "${method.declaringClass.name}.${method.name}$paramTypesString"
    }

    private fun resolveGenericType(method: Method): Class<*> =
        method.genericParameterTypes.getOrNull(0)?.let { genericType ->
            ReflectionUtils.resolveUpperBoundSuperClassGenericParameter(
                genericType,
                GenericSkyHanniEvent::class.java.typeParameters[0],
            ) ?: error(
                "Generic event handler type parameter is not present in " +
                    "event class hierarchy for type $genericType",
            )
        } ?: error("Method ${method.name} does not have a generic parameter type.")

    fun getListeners(): List<Listener> = listeners.toList()

    class Listener(
        val name: String,
        val invoker: Consumer<Any>,
        options: HandleEvent,
        val generic: Class<*>?,
        val extraPredicates: List<(SkyHanniEvent) -> Boolean> = emptyList(),
        val method: Method? = null,
        val instance: Any? = null,
    ) {
        val priority: Int = options.priority
        val receiveCancelled: Boolean = options.receiveCancelled

        private val states: BooleanArray = ListenerCollection.createStateMask(options)
        private val features: Array<OutsideSBFeature> = options.onlyOnSkyblockOrFeatures

        val needsEventCheck: Boolean = generic != null

        fun isActiveIn(state: Int): Boolean = state in states.indices && states[state]

        /**
         * Evaluated when the handler snapshot is rebuilt
         */
        fun passesStaticChecks(): Boolean {
            if (SkyHanniEvents.isDisabledInvoker(name)) return false
            if (features.isNotEmpty()) {
                @Suppress("DEPRECATION")
                if (!SkyBlockUtils.inSkyBlock && features.none { it.isSelected() }) return false
            }
            return true
        }

        /** Checks that need the posted event. Only called if [needsEventCheck]. */
        fun matches(event: SkyHanniEvent): Boolean {
            if (generic != null && !(event is GenericSkyHanniEvent<*> && generic.isAssignableFrom(event.type))) {
                return false
            }
            return true
        }
    }
}
