package at.hannibal2.skyhanni.data.title

import at.hannibal2.skyhanni.SkyHanniMod
import at.hannibal2.skyhanni.api.event.HandleEvent
import at.hannibal2.skyhanni.config.commands.CommandCategory
import at.hannibal2.skyhanni.config.commands.CommandRegistrationEvent
import at.hannibal2.skyhanni.config.commands.brigadier.BrigadierArguments
import at.hannibal2.skyhanni.events.DebugDataCollectEvent
import at.hannibal2.skyhanni.skyhannimodule.SkyHanniModule
import at.hannibal2.skyhanni.utils.ChatUtils
import at.hannibal2.skyhanni.utils.InventoryUtils
import at.hannibal2.skyhanni.utils.SimpleTimeMark
import at.hannibal2.skyhanni.utils.TimeUtils
import at.hannibal2.skyhanni.utils.TimeUtils.format
import at.hannibal2.skyhanni.utils.collection.CollectionUtils
import at.hannibal2.skyhanni.utils.collection.CollectionUtils.enumMapOf
import at.hannibal2.skyhanni.utils.renderables.Renderable
import at.hannibal2.skyhanni.utils.renderables.primitives.text
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@SkyHanniModule
object TitleManager {

    private val titleLocationQueues: MutableMap<TitleLocation, CollectionUtils.OrderedQueue<TitleContext>> = enumMapOf()
    private val currentTitles: MutableMap<TitleLocation, TitleContext?> = enumMapOf()
    val guiConfig get() = SkyHanniMod.feature.gui
    val intentionMapper: MutableMap<String, TitleIntention> = mutableMapOf()

    enum class TitleLocation(
        private val displayName: String,
        val activationRequirement: () -> Boolean = { true },
    ) {
        GLOBAL("Global"),
        INVENTORY("Inventory", activationRequirement = { InventoryUtils.inInventory() }),
        ;

        override fun toString() = displayName
    }

    enum class TitleAddType(private val displayName: String) {
        FORCE_FIRST("Force First"),
        QUEUE("Queue"),
        ;

        override fun toString() = displayName
    }

    private fun <E : Enum<E>> parseIntention(intention: E?): TitleIntention? = when (intention) {
        null -> null
        else -> intentionMapper[intention.name]
    }

    /**
     * Send a title using a pre-built Renderable component.
     */
    fun <E : Enum<E>> sendTitle(
        title: Renderable,
        subtitle: Renderable? = null,
        duration: Duration = 5.seconds,
        intention: E? = null,
        location: TitleLocation = TitleLocation.GLOBAL,
        addType: TitleAddType = TitleAddType.QUEUE,
        weight: Double = 1.0,
        discardOnWorldChange: Boolean = true,
        noDuplicates: Boolean = true,
    ): TitleContext? = sendTitleInternal(
        TitleContext(title, subtitle, parseIntention(intention), duration, weight, discardOnWorldChange),
        location,
        addType,
        noDuplicates
    )

    /**
     * Send a dynamic/reactive title using suppliers (() -> Renderable) for live updates (e.g., timers & countdowns).
     */
    fun <E : Enum<E>> sendTitle(
        titleSupplier: () -> Renderable,
        subtitleSupplier: (() -> Renderable)? = null,
        duration: Duration = 5.seconds,
        intention: E? = null,
        location: TitleLocation = TitleLocation.GLOBAL,
        addType: TitleAddType = TitleAddType.QUEUE,
        weight: Double = 1.0,
        discardOnWorldChange: Boolean = true,
        noDuplicates: Boolean = true,
    ): TitleContext? = sendTitleInternal(
        TitleContext(titleSupplier, subtitleSupplier, parseIntention(intention), duration, weight, discardOnWorldChange),
        location,
        addType,
        noDuplicates
    )

    /**
     * Convenience overload for legacy String titles with color code support.
     */
    fun <E : Enum<E>> sendTitle(
        titleText: String,
        subtitleText: String? = null,
        duration: Duration = 5.seconds,
        intention: E? = null,
        location: TitleLocation = TitleLocation.GLOBAL,
        addType: TitleAddType = TitleAddType.QUEUE,
        weight: Double = 1.0,
        discardOnWorldChange: Boolean = true,
        noDuplicates: Boolean = true,
    ): TitleContext? {
        val formattedTitle = "§6" + titleText.replace("&", "§")
        val formattedSubtitle = subtitleText?.let { "§6" + it.replace("&", "§") }
        return sendTitle(
            title = Renderable.text(formattedTitle, scale = 3.0),
            subtitle = formattedSubtitle?.let { Renderable.text(it, scale = 2.25) },
            duration = duration,
            intention = intention,
            location = location,
            addType = addType,
            weight = weight,
            discardOnWorldChange = discardOnWorldChange,
            noDuplicates = noDuplicates
        )
    }

    /**
     * Send a timed/countdown title supporting %t (duration string) and %f (formatted time) placeholders.
     */
    fun <E : Enum<E>> sendTimedTitle(
        titleTemplate: String,
        subtitleTemplate: String? = null,
        duration: Duration = 5.seconds,
        intention: E? = null,
        location: TitleLocation = TitleLocation.GLOBAL,
        addType: TitleAddType = TitleAddType.QUEUE,
        weight: Double = 1.0,
        discardOnWorldChange: Boolean = true,
        noDuplicates: Boolean = true,
        onInterval: (() -> Unit)? = null,
    ): TitleContext? {
        val endTime = SimpleTimeMark.now() + duration
        var lastWholeSecond = -1

        fun String.formatTimedString(remaining: Duration): String {
            return this
                .replace("%t", remaining.toString())
                .replace("%f", remaining.format())
        }

        return sendTitle(
            titleSupplier = {
                val remaining = endTime.timeUntil().coerceAtLeast(Duration.ZERO)
                val wholeSeconds = remaining.inWholeSeconds.toInt()
                if (onInterval != null && wholeSeconds != lastWholeSecond && wholeSeconds >= 0) {
                    lastWholeSecond = wholeSeconds
                    onInterval()
                }
                Renderable.text(titleTemplate.formatTimedString(remaining), scale = 3.0)
            },
            subtitleSupplier = subtitleTemplate?.let { template ->
                {
                    val remaining = endTime.timeUntil().coerceAtLeast(Duration.ZERO)
                    Renderable.text(template.formatTimedString(remaining), scale = 2.25)
                }
            },
            duration = duration,
            intention = intention,
            location = location,
            addType = addType,
            weight = weight,
            discardOnWorldChange = discardOnWorldChange,
            noDuplicates = noDuplicates
        )
    }

    private fun sendTitleInternal(
        newTitle: TitleContext,
        location: TitleLocation,
        addType: TitleAddType,
        noDuplicates: Boolean,
    ): TitleContext? {
        val targetQueue = titleLocationQueues.getOrPut(location) { CollectionUtils.OrderedQueue() }
        if (targetQueue.any { it.item == newTitle } && noDuplicates) return null

        val weightOverride = if (addType == TitleAddType.FORCE_FIRST) Double.MAX_VALUE else newTitle.weight
        targetQueue.add(newTitle, weightOverride)

        // Instantly evaluate to ensure a newly added title can claim its spot if current slot is empty or lower weight
        updateLocationState(location)
        return newTitle
    }

    fun conditionallyStopTitle(
        location: TitleLocation? = TitleLocation.GLOBAL,
        condition: (TitleContext) -> Boolean,
    ) = when (location) {
        null -> currentTitles.values.filterNotNull()
            .filter(condition)
            .forEach { it.stop() }

        else -> currentTitles[location]?.let { title ->
            if (condition(title)) title.stop()
        }
    }

    @HandleEvent
    private fun onCommandRegistration(event: CommandRegistrationEvent) {
        event.registerBrigadier("shsendtitle") {
            description = "Display a title on the screen with the specified settings."
            category = CommandCategory.DEVELOPER_TEST
            arg("duration", BrigadierArguments.string()) { duration ->
                arg("title", BrigadierArguments.greedyString()) { title ->
                    callback { command(getArg(duration), getArg(title)) }
                }
            }
            literal("reset") {
                callback { resetCommand() }
            }
        }
        event.registerBrigadier("shsendinventorytitle") {
            description = "Display a title on the inventory screen with the specified settings."
            category = CommandCategory.DEVELOPER_TEST
            arg("duration", BrigadierArguments.string()) { duration ->
                arg("title", BrigadierArguments.greedyString()) { title ->
                    callback { command(getArg(duration), getArg(title), TitleLocation.INVENTORY) }
                }
            }
            literal("reset") {
                callback { resetCommand() }
            }
        }
    }

    private fun resetCommand() {
        titleLocationQueues.clear()
        for (context in currentTitles.values) {
            context?.stop()
        }
        currentTitles.clear()
        ChatUtils.chat("Reset all active titles!")
    }

    private fun command(
        durationText: String,
        titleText: String,
        location: TitleLocation = TitleLocation.GLOBAL,
    ) {
        val duration = TimeUtils.getDurationOrNull(durationText) ?: run {
            ChatUtils.userError("Invalid duration format `$durationText`! Use e.g. 10s, or 20m or 30h")
            return
        }
        sendTitle(titleText, location = location, duration = duration)
    }

    @HandleEvent
    private fun onDebugDataCollect(event: DebugDataCollectEvent) {
        event.title("TitleManager")
        event.addIrrelevant {
            add(
                "Title Location Queues" + titleLocationQueues.let { queues ->
                    queues.entries.joinToString { queue ->
                        "${queue.key}:\n" + buildString {
                            appendLine("Title Queue: ${queue.value.size}")
                            queue.value.forEach { queuedTitle ->
                                appendLine(queuedTitle.item.toString())
                            }
                        }
                    }
                },
            )
            add(
                "Current titles" + currentTitles.let { titles ->
                    titles.entries.joinToString("\n\n") { title ->
                        "${title.key}:\n" + buildString {
                            val titleItem = title.value ?: return@buildString
                            appendLine(titleItem.toString())
                        }
                    }
                },
            )
        }
    }

    @HandleEvent
    private fun onWorldChange() {
        titleLocationQueues.forEach { queue ->
            val (location, titleQueue) = queue
            titleLocationQueues[location] = titleQueue.copyWithFilter { !it.discardOnWorldChange }
        }
        currentTitles.forEach { (location, titleContext) ->
            if (titleContext == null || !titleContext.discardOnWorldChange) return@forEach
            titleContext.stop()
            currentTitles[location] = null
            updateLocationState(location)
        }
    }

    @HandleEvent
    private fun onProfileJoin() {
        stop()
    }

    @HandleEvent
    private fun onInventoryClose() {
        stop(TitleLocation.INVENTORY)
    }

    private fun stop(location: TitleLocation? = null) {
        when (location) {
            null -> {
                currentTitles.values.filterNotNull().forEach { it.stop() }
                currentTitles.clear()
            }
            else -> {
                currentTitles[location]?.stop()
                currentTitles[location] = null
            }
        }
    }

    /**
     * Lazily and reactively updates the state (expiration, queue preemption, and polling)
     * for a location right when it's accessed or rendered, removing the need for a ticker.
     */
    private fun getActiveTitle(location: TitleLocation): TitleContext? {
        if (!location.activationRequirement()) return null

        val currentTitle = currentTitles[location]
        val titleQueue = titleLocationQueues[location]

        // 1. Check if current title expired or needs to be stopped
        if (currentTitle != null && !currentTitle.alive) {
            currentTitle.stop()
            currentTitles[location] = null
        }

        // 2. Check if a waiting item has higher/equal weight to preempt current title (requeue logic)
        val waitingWeight = titleQueue?.getWaitingWeightOrNull()
        val activeCurrent = currentTitles[location]

        if (waitingWeight != null && activeCurrent != null && activeCurrent.alive) {
            if (waitingWeight >= activeCurrent.weight && activeCurrent.processRequeue()) {
                titleQueue.add(activeCurrent, activeCurrent.weight)
                currentTitles[location] = null
            }
        }

        // 3. Pull next title if slot is empty
        if (currentTitles[location] == null) {
            val nextTitle = titleQueue?.pollOrNull()
            if (nextTitle != null) {
                nextTitle.start()
                currentTitles[location] = nextTitle
            }
        }

        val resolved = currentTitles[location]
        if (resolved != null && !resolved.alive) {
            resolved.stop()
            currentTitles[location] = null
            return getActiveTitle(location) // Recurse to pull subsequent if needed
        }

        return resolved
    }

    private fun updateLocationState(location: TitleLocation) {
        getActiveTitle(location)
    }

    @HandleEvent
    private fun onGuiRenderOverlay() {
        if (InventoryUtils.inInventory()) return
        val globalTitle = getActiveTitle(TitleLocation.GLOBAL) ?: return
        globalTitle.tryRenderGlobalTitle()
    }

    @HandleEvent
    private fun onChestGuiRender() {
        if (!InventoryUtils.inInventory()) return
        val inventoryTitle = getActiveTitle(TitleLocation.INVENTORY) ?: return
        inventoryTitle.tryRenderInventoryTitle()
    }
}
