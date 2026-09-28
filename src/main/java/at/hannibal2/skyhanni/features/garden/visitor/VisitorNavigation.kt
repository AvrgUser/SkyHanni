package at.hannibal2.skyhanni.features.garden.visitor

import at.hannibal2.skyhanni.SkyHanniMod.launch
import at.hannibal2.skyhanni.api.event.HandleEvent
import at.hannibal2.skyhanni.config.commands.CommandRegistrationEvent
import at.hannibal2.skyhanni.config.commands.brigadier.BrigadierArguments
import at.hannibal2.skyhanni.data.IslandGraphs
import at.hannibal2.skyhanni.data.IslandType
import at.hannibal2.skyhanni.data.WarpApi
import at.hannibal2.skyhanni.data.jsonobjects.repo.GardenJson
import at.hannibal2.skyhanni.data.jsonobjects.repo.GardenVisitor
import at.hannibal2.skyhanni.events.RepositoryReloadEvent
import at.hannibal2.skyhanni.features.commands.WikiManager
import at.hannibal2.skyhanni.features.misc.pathfind.NavigateAllApi
import at.hannibal2.skyhanni.skyhannimodule.SkyHanniModule
import at.hannibal2.skyhanni.utils.ChatUtils
import at.hannibal2.skyhanni.utils.LorenzColor
import at.hannibal2.skyhanni.utils.LorenzVec
import at.hannibal2.skyhanni.utils.SkyBlockUtils
import at.hannibal2.skyhanni.utils.StringUtils
import at.hannibal2.skyhanni.utils.collection.CollectionUtils.contains
import at.hannibal2.skyhanni.utils.coroutines.CoroutineSettings

@SkyHanniModule
object VisitorNavigation {
    private data class VisitorNavigationData(
        val island: IslandType,
        val rawPosition: LorenzVec?,
        val name: String,
    ) {
        suspend fun getPosition(): LorenzVec? {
            if (island == SkyBlockUtils.currentIsland) {
                val graph = IslandGraphs.currentIslandGraph ?: return rawPosition
                val nodes = graph.getNodesWithTags(NPC)
                return nodes.firstOrNull { it.name == name }?.position ?: rawPosition
            }

            val allPositions = allVisitorPositions ?: run {
                allVisitorPositions = IslandGraphs.loadAllNpcsLocations { }
                allVisitorPositions
            }

            return allPositions?.get(island)?.get(name) ?: rawPosition
        }
    }

    private var visitors = mapOf<IslandType, List<VisitorNavigationData>>()
    private var noIslandVisitors = setOf<String>()

    private var allVisitorPositions: Map<IslandType, Map<String, LorenzVec>>? = null

    private val currentIslandVisitors get() = visitors[SkyBlockUtils.currentIsland].orEmpty()

    @HandleEvent
    private fun onRepoReload(event: RepositoryReloadEvent) {
        val visitors = event.getConstant<GardenJson>("Garden").visitors
        loadVisitors(visitors)
        allVisitorPositions = null
    }

    private fun loadVisitors(visitorsJson: Map<String, GardenVisitor>) {
        val otherVisitors = mutableSetOf<String>()

        val visitorsByIsland = visitorsJson.entries
            .groupBy { it.value.mode }
            .mapNotNull { (mode, visitors) ->
                val island = mode?.let(IslandType::getByIdOrNull) ?: run {
                    otherVisitors += visitors.map { it.key }
                    return@mapNotNull null
                }
                island to visitors
            }

        this.visitors = visitorsByIsland.associate { (island, visitors) ->
            val navigationData = visitors.mapNotNull { (name, visitor) ->
                val position = visitor.position ?: run {
                    otherVisitors += name
                    return@mapNotNull null
                }

                VisitorNavigationData(
                    island = island,
                    rawPosition = position,
                    name = name,
                )
            }

            island to navigationData
        }

        noIslandVisitors = otherVisitors
    }

    @HandleEvent
    private fun onCommandRegistration(event: CommandRegistrationEvent) {
        event.registerBrigadier("shvisitornav") {
            description = "Navigates to visitors either on the current island or other islands, if they have a fixed location"
            category = USERS_ACTIVE

            coroutineArgCallback(
                "visitor",
                BrigadierArguments.greedyString(),
                getVisitorSuggestions(),
            ) { name ->
                val visitor = currentIslandVisitors.firstOrNull {
                    it.name.equals(name, ignoreCase = true)
                }

                if (visitor == null) {
                    visitorNotFound(name)
                    return@coroutineArgCallback
                }

                startNavigation(visitor)
            }
        }

        event.registerBrigadier("shvisitornavall") {
            description = "Navigates to all visitors on the current island"
            category = USERS_ACTIVE

            coroutineSimpleCallback {
                startAllNavigation()
            }
        }
    }

    // Suggests visitors from the current island, other islands, and visitors without a fixed location, in that order.
    private fun getVisitorSuggestions(): List<String> {
        val currentIsland = currentIslandVisitors.map { it.name }
        val otherIslands = visitors
            .filterKeys { it != SkyBlockUtils.currentIsland }
            .values
            .flatten()
            .map { it.name }
        return (currentIsland + otherIslands + noIslandVisitors).toList()
    }

    private fun noPositionFound(visitor: VisitorNavigationData) {
        ChatUtils.userError(
            "Visitor §a'${visitor.name}' §ccould not be located."
        )
        WikiManager.sendWikiMessage(visitor.name, autoOpen = false)
    }

    private suspend fun visitorNotFound(rawName: String) {
        val visitor = visitors.values
            .flatten()
            .firstOrNull { it.name.equals(rawName, ignoreCase = true) }

        if (visitor == null) {
            if (!noIslandVisitors.contains(rawName, ignoreCase = true)) {
                ChatUtils.userError("Visitor §a'$rawName' §ccould not be found.")
                return
            }

            ChatUtils.userError(
                "Visitor §a'$rawName' §cdoes not have a fixed position. " +
                    "Some visitors only appear under specific conditions."
            )
            WikiManager.sendWikiMessage(rawName, autoOpen = false)
            return
        }

        val position = visitor.getPosition() ?: run {
            noPositionFound(visitor)
            return
        }

        ChatUtils.chat(
            "§7Visitor §a'${visitor.name}' §7is at §a${visitor.island.displayName}§7."
        )

        WarpApi.sendWarpMessage(
            position = position,
            island = visitor.island,
            shouldRetry = true,
            onWarp = {
                CoroutineSettings("visitor navigation after warp").launch {
                    startNavigation(visitor)
                }
            },
            onFail = {
                ChatUtils.chat(
                    "§7Could not find a working warp to §a'${visitor.name}'§7."
                )
            },
        )
    }

    private fun startAllNavigation() {
        val graph = IslandGraphs.currentIslandGraph ?: return

        val npcNodes = graph.getNodesWithTags(NPC)

        val nodes = currentIslandVisitors.mapNotNull { visitor ->
            npcNodes.firstOrNull { it.name == visitor.name }
                ?: visitor.rawPosition?.let { graph.getNearestNode(it) }
        }

        NavigateAllApi.navigateAll(
            nodes,
            targetName = "Visitor",
            color = LorenzColor.DARK_PURPLE.toColor(),
            onFinish = {
                ChatUtils.chat(
                    "Reached all ${StringUtils.pluralize(nodes.size, "§aVisitor", withNumber = true)}§e."
                )
            },
            continueNavigationCondition = None,
            condition = { true },
        )
    }

    private suspend fun startNavigation(visitor: VisitorNavigationData) {
        val position = visitor.getPosition() ?: run {
            noPositionFound(visitor)
            return
        }

        IslandGraphs.pathFind(
            location = position,
            label = visitor.name,
            color = LorenzColor.DARK_PURPLE.toColor(),
            condition = { true },
        )
    }
}
