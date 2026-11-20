package at.hannibal2.skyhanni.api.event

import at.hannibal2.skyhanni.config.enums.OutsideSBFeature
import at.hannibal2.skyhanni.utils.ReflectionUtils
import at.hannibal2.skyhanni.utils.SkyBlockUtils
import java.lang.reflect.Method
import java.util.function.Consumer

typealias EventPredicate = (event: SkyHanniEvent) -> Boolean

class EventListeners private constructor(val name: String, private val isGeneric: Boolean) {

    private val listeners: MutableList<Listener> = mutableListOf()

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
        /** The handler method and its owner, used to find this listener again when unregistering. */
        val method: Method? = null,
        val instance: Any? = null,
    ) {
        val priority: Int = options.priority
        val receiveCancelled: Boolean = options.receiveCancelled

        private val states: BooleanArray = ListenerCollection.createStateMask(options)
        private val features: Array<OutsideSBFeature> = options.onlyOnSkyblockOrFeatures

        fun isActiveIn(state: Int): Boolean = state in states.indices && states[state]

        /**
         * Checks that never look at the event. Evaluated when the handler snapshot is rebuilt
         * (island change, repo reload, feature toggle), not on every post.
         */
        fun passesStaticChecks(): Boolean {
            if (SkyHanniEvents.isDisabledInvoker(name)) return false
            if (features.isNotEmpty()) {
                @Suppress("DEPRECATION")
                if (!SkyBlockUtils.inSkyBlock && features.none { it.isSelected() }) return false
            }
            return true
        }
    }
}
