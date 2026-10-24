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
import at.hannibal2.skyhanni.utils.TimeUtils
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
            dequeueNextTitle(location)
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
            null -> currentTitles.values.filterNotNull().forEach { it.stop() }
            else -> currentTitles[location]?.stop()
        }
    }

    @HandleEvent
    private fun onTick() {
        TitleLocation.entries.filter {
            it.activationRequirement.invoke()
        }.forEach { location ->
            when (val currentTitle = currentTitles[location]) {
                null -> dequeueNextTitle(location)
                else -> {
                    val titleLocationQueue = titleLocationQueues[location]
                    titleLocationQueue?.getWaitingWeightOrNull()?.let { waitingWeight ->
                        if (waitingWeight < currentTitle.weight) return@let
                        if (!currentTitle.alive) return@let
                        if (!currentTitle.processRequeue()) return@let
                        titleLocationQueue.add(currentTitle, currentTitle.weight)
                        dequeueNextTitle(location)
                        return@forEach
                    }
                    if (currentTitle.alive) return@forEach
                    currentTitle.stop()
                    dequeueNextTitle(location)
                }
            }
        }
        // Watchdog
        TitleLocation.entries.forEach {
            currentTitles[it]?.start()
            if (currentTitles[it]?.alive == false) {
                currentTitles[it]?.stop()
                currentTitles[it] = null
            }
        }
    }

    private fun dequeueNextTitle(location: TitleLocation) {
        val titleQueue = titleLocationQueues[location]
        val title = titleQueue?.pollOrNull()
        currentTitles[location] = title
    }

    @HandleEvent
    private fun onGuiRenderOverlay() {
        if (InventoryUtils.inInventory()) return
        val globalTitle = currentTitles[TitleLocation.GLOBAL] ?: return
        globalTitle.tryRenderGlobalTitle()
    }

    @HandleEvent
    private fun onChestGuiRender() {
        if (!InventoryUtils.inInventory()) return
        val inventoryTitle = currentTitles[TitleLocation.INVENTORY] ?: return
        inventoryTitle.tryRenderInventoryTitle()
    }
}
