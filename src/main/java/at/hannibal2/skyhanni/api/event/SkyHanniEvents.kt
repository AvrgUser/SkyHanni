package at.hannibal2.skyhanni.api.event

import at.hannibal2.skyhanni.SkyHanniMod
import at.hannibal2.skyhanni.data.jsonobjects.repo.DisabledEventVersionedJson
import at.hannibal2.skyhanni.data.jsonobjects.repo.DisabledEventsJson
import at.hannibal2.skyhanni.events.RepositoryReloadEvent
import at.hannibal2.skyhanni.skyhannimodule.SkyHanniModule
import at.hannibal2.skyhanni.test.command.ErrorManager
import at.hannibal2.skyhanni.utils.system.ModVersion
import at.hannibal2.skyhanni.utils.system.PlatformUtils
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@SkyHanniModule
object SkyHanniEvents {
    // Mutex for Writes (register, unregister, handler creation)
    private val lock = Any()
    private val listeners: MutableMap<Class<out SkyHanniEvent>, EventListeners> = ConcurrentHashMap()
    private val handlers: MutableMap<Class<out SkyHanniEvent>, EventHandler<out SkyHanniEvent>> = ConcurrentHashMap()

    @Volatile
    private var disabledHandlers = emptySet<String>()

    @Volatile
    private var disabledHandlerInvokers = emptySet<String>()

    fun init(instances: List<Any>) = instances.forEach(::register)

    fun register(instance: Any) {
        instance.javaClass.declaredMethods.forEach {
            registerMethod(it, instance)
        }
    }

    fun unregister(instance: Any) = instance.javaClass.declaredMethods.forEach { unregisterMethod(it, instance) }

    @Suppress("UNCHECKED_CAST")
    fun <T : SkyHanniEvent> getEventHandler(event: Class<T>): EventHandler<T> {
        // Hot path: one lock-free map read.
        handlers[event]?.let { return it as EventHandler<T> }

        return synchronized(lock) {
            handlers.getOrPut(event) {
                EventHandler(
                    event,
                    getEventClasses(event).mapNotNull { listeners[it] }.flatMap(EventListeners::getListeners),
                )
            } as EventHandler<T>
        }
    }

    fun isDisabledHandler(handler: String): Boolean = handler in disabledHandlers
    fun isDisabledInvoker(invoker: String): Boolean = invoker in disabledHandlerInvokers

    private fun registerMethod(method: Method, instance: Any) {
        val (options, eventTypes) = getEventData(method) ?: return
        synchronized(lock) {
            eventTypes.forEach { eventType ->
                listeners.getOrPut(eventType) { EventListeners(eventType) }
                    .addListener(method, instance, options)
                invalidateHandlers(eventType)
            }
        }
    }

    @JvmStatic
    val eventPrimaryFunctionNames: Map<String, Class<out SkyHanniEvent>> =
        GeneratedEventPrimaryFunctionNames.map

    private val Method.fullyQualifiedName: String get() = "${declaringClass.name}.$name"

    private fun handleZeroParameterMethod(
        method: Method,
        options: HandleEvent,
    ): Pair<HandleEvent, List<Class<out SkyHanniEvent>>>? {
        val primaryFunctionEventType = eventPrimaryFunctionNames[method.name]
        if (primaryFunctionEventType != null) return options to listOf(primaryFunctionEventType)

        if (options.eventType != SkyHanniEvent::class) return options to listOf(options.eventType.java)

        if (options.eventTypes.isEmpty()) {
            ErrorManager.crashInDevEnv(
                "Function ${method.fullyQualifiedName} must have an event parameter, a primary " +
                    "function name, or an explicit event specification because it is annotated " +
                    "with @HandleEvent",
            )
            return null
        }

        return options to options.eventTypes.map { it.java }
    }

    private fun handleSingleParameterMethod(
        method: Method,
        options: HandleEvent,
    ): Pair<HandleEvent, List<Class<out SkyHanniEvent>>>? {
        val eventType = method.parameterTypes.first()

        if (!SkyHanniEvent::class.java.isAssignableFrom(eventType)) {
            ErrorManager.crashInDevEnv(
                "Function ${method.fullyQualifiedName} must have an event assignable from " +
                    "SkyHanniEvent because it is annotated with @HandleEvent",
            )
            return null
        }

        @Suppress("UNCHECKED_CAST")
        return options to listOf(eventType as Class<out SkyHanniEvent>)
    }

    private fun getEventData(method: Method): Pair<HandleEvent, List<Class<out SkyHanniEvent>>>? {
        val options = method.getAnnotation(HandleEvent::class.java) ?: return null
        if (!method.declaringClass.isAnnotationPresent(SkyHanniModule::class.java)) {
            ErrorManager.crashInDevEnv(
                "Function ${method.fullyQualifiedName} must be declared directly inside a class " +
                    "annotated with @SkyHanniModule because it is annotated with @HandleEvent",
            )
            return null
        }
        return when (method.parameterCount) {
            0 -> handleZeroParameterMethod(method, options)
            1 -> handleSingleParameterMethod(method, options)
            else -> {
                ErrorManager.crashInDevEnv(
                    "Function ${method.fullyQualifiedName} has too many parameters. It must have " +
                        "exactly one event parameter, or be parameterless with a primary " +
                        "function name or an explicit event specification because it is " +
                        "annotated with @HandleEvent",
                )
                null
            }
        }
    }

    private fun unregisterMethod(method: Method, instance: Any) {
        val (_, eventTypes) = getEventData(method) ?: return
        synchronized(lock) {
            eventTypes.forEach { event ->
                listeners[event]?.removeListener(method, instance)
                invalidateHandlers(event)
            }
        }
    }

    /**
     * A handler also contains the listeners of the superclasses of its event, so every handler whose event is
     * [clazz] or a subclass of it has to be rebuilt. They are recreated lazily on the next post.
     */
    private fun invalidateHandlers(clazz: Class<out SkyHanniEvent>) {
        handlers.keys.removeIf { clazz.isAssignableFrom(it) }
    }

    private val listenerCacheGeneration = AtomicInteger(0)
    private val currentStateIndex = AtomicInteger(ListenerCollection.OUTSIDE)

    fun markEventCacheDirty(type: DirtyReason) {
        when (type) {
            REPO_RELOAD,
            OUTSIDE_SB_FEATURE_CHANGED,
            -> listenerCacheGeneration.incrementAndGet()

            LOCATION_CHANGED -> {
                listenerCacheGeneration.incrementAndGet()
                currentStateIndex.set(ListenerCollection.getCurrentStateIndex())
            }

            SERVER_DISCONNECTED -> {
                listenerCacheGeneration.incrementAndGet()
                currentStateIndex.set(ListenerCollection.OUTSIDE)
            }
        }
    }

    fun getListenerCacheGeneration(): Int = listenerCacheGeneration.get()
    fun getCurrentStateIndex(): Int = currentStateIndex.get()

    enum class DirtyReason {
        LOCATION_CHANGED,
        OUTSIDE_SB_FEATURE_CHANGED,
        SERVER_DISCONNECTED,
        REPO_RELOAD,
    }

    // This is marked highest priority to let it disable other RepositoryReloadEvent listeners
    // before they happen. All other listeners of this event must use HIGH or lower priority.
    @HandleEvent(priority = HandleEvent.HIGHEST)
    private fun onRepoReload(event: RepositoryReloadEvent) {
        val data = event.getConstant<DisabledEventsJson>("DisabledEvents")
        val version = SkyHanniMod.modVersion

        val mcVersion = PlatformUtils.MC_VERSION
        disabledHandlers = data.disabledHandlers + data.disabledHandlersVersioned.activeNames(version, mcVersion)
        disabledHandlerInvokers = data.disabledInvokers + data.disabledInvokersVersioned.activeNames(version, mcVersion)
        markEventCacheDirty(REPO_RELOAD)
    }

    private fun Set<DisabledEventVersionedJson>.activeNames(
        version: ModVersion,
        mcVersion: String,
    ): Set<String> =
        filter {
            (it.minVersion == null || version >= it.minVersion) &&
                (it.maxVersion == null || version <= it.maxVersion) &&
                (it.mcVersions == null || mcVersion in it.mcVersions)
        }.map { it.name }.toSet()

    val seconds = listOf(10, 60, 60 * 5)

    /**
     * Returns a list of all super classes and the class itself up to [SkyHanniEvent].
     */
    private fun getEventClasses(clazz: Class<*>): List<Class<*>> {
        val classes = mutableListOf<Class<*>>()
        classes.add(clazz)

        var current = clazz
        @Suppress("LoopWithTooManyJumpStatements")
        while (current.superclass != null) {
            val superClass = current.superclass
            if (superClass == SkyHanniEvent::class.java) break
            if (superClass == GenericSkyHanniEvent::class.java) break
            if (superClass == RenderingSkyHanniEvent::class.java) break
            if (superClass == CancellableSkyHanniEvent::class.java) break
            classes.add(superClass)
            current = superClass
        }
        return classes
    }
}
