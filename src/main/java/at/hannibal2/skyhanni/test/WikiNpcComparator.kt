package at.hannibal2.skyhanni.test

import at.hannibal2.skyhanni.SkyHanniMod.launchCoroutine
import at.hannibal2.skyhanni.api.event.HandleEvent
import at.hannibal2.skyhanni.config.commands.CommandCategory
import at.hannibal2.skyhanni.config.commands.CommandRegistrationEvent
import at.hannibal2.skyhanni.data.IslandGraphs
import at.hannibal2.skyhanni.data.IslandType
import at.hannibal2.skyhanni.skyhannimodule.SkyHanniModule
import at.hannibal2.skyhanni.utils.ChatUtils
import at.hannibal2.skyhanni.utils.LorenzVec
import at.hannibal2.skyhanni.utils.NumberUtil.roundTo
import at.hannibal2.skyhanni.utils.OSUtils
import at.hannibal2.skyhanni.utils.collection.CollectionUtils.removeIf
import at.hannibal2.skyhanni.utils.coroutines.CoroutineSettings

@SkyHanniModule
object WikiNpcComparator {

    @HandleEvent
    private fun onCommandRegistration(event: CommandRegistrationEvent) {
        event.registerBrigadier("shcomparewikinpc") {
            description = "Compare NPC locations from wiki (clipboard) with SkyHanni graph data."
            category = CommandCategory.DEVELOPER_TEST
            legacyCallbackArgs {
                CoroutineSettings("compare wiki npc data").launchCoroutine {
                    val result = mutableListOf<String>()
                    val wikiNpcs = customRules(loadWiki(result)) ?: return@launchCoroutine
                    // TODO do this once on skyblock join, then store until repo reload.
                    val shNpcs = IslandGraphs.loadAllNpcsLocations(result::add) ?: return@launchCoroutine
                    compare(result, shNpcs, wikiNpcs)
                }
            }
        }
    }

    private fun customRules(raw: MutableMap<IslandType, MutableMap<String, LorenzVec>>?):
        MutableMap<IslandType, MutableMap<String, LorenzVec>>? {
        if (raw == null) return null
        for ((_, names) in raw) {
            for ((name, location) in names.toMutableMap()) {
                if (name.contains(" (Dwarven)")) {
                    val updatedName = name.removeSuffix(" (Dwarven)")
                    names[updatedName] = location
                    names.remove(name)
                }
            }
        }

        return raw
    }

    private fun loadWiki(result: MutableList<String>): MutableMap<IslandType, MutableMap<String, LorenzVec>>? {
        val clipboard = OSUtils.readFromClipboard() ?: run {
            ChatUtils.userError("clipboard does not contain a string")
            return null
        }
        val wikiNpcs = WikiNpcParser.parse(clipboard)
        val total = wikiNpcs.values.sumOf { it.size }
        result.add("found in total $total npcs in wiki")
        if (total == 0) {
            ChatUtils.clickableChat(
                "found no npc data in the html file, click here to copy.",
                onClick = {
                    OSUtils.openBrowser("https://hypixelskyblock.minecraft.wiki/w/NPC/List")
                },
            )
            return null
        }
        return wikiNpcs
    }

    private fun compare(
        result: MutableList<String>,
        shNpcs: Map<IslandType, Map<String, LorenzVec>>,
        wikiNpcs: Map<IslandType, Map<String, LorenzVec>>,
    ) {
        val allIslands = (shNpcs.keys + wikiNpcs.keys).sorted()

        var allFine = 0
        var issues = 0
        for (island in allIslands) {
            val sh = shNpcs[island].orEmpty()
            val wiki = wikiNpcs[island].orEmpty()
            val allNames = (sh.keys + wiki.keys).sorted()
            val islandLines = mutableMapOf<String, List<String>>()

            val onlyInSh = mutableListOf<String>()
            islandLines["Only in skyhanni"] = onlyInSh
            val onlyInWiki = mutableListOf<String>()
            islandLines["Only in wiki"] = onlyInWiki
            val mismatch = mutableListOf<String>()
            islandLines["mismatch"] = mismatch

            for (name in allNames) {
                val shPos = sh[name]
                val wikiPos = wiki[name]

                when {
                    shPos != null && wikiPos == null -> onlyInSh.add("$name: ${shPos.toChatFormat()}")

                    shPos == null && wikiPos != null -> onlyInWiki.add("$name: ${wikiPos.toChatFormat()}")

                    shPos != null && wikiPos != null -> {
                        val dist = shPos.distance(wikiPos)
                        /*
                         * this cant be a precise check.
                         * the wiki uses the block location the npc stands on,
                         * and skyhanni uses the location of where the user should stand when talking to the npc
                         * so this is almost never 0 block distance, and often even 2 blocks distance.
                         */
                        if (dist > 3.0) {
                            mismatch.add(
                                "$name: ${dist.roundTo(1)} blocks away\n" +
                                    "      sh: ${shPos.toChatFormat()}\n" +
                                    "      wiki: ${wikiPos.toChatFormat()}",
                            )
                        } else {
                            allFine++
                        }
                    }
                }
            }

            islandLines.removeIf { it.value.isEmpty() }
            if (islandLines.isNotEmpty()) {
                result.add("=== $island (sh=${sh.size}, wiki=${wiki.size}) ===")
                for ((type, list) in islandLines) {
                    if (list.isEmpty()) continue
                    result.add("  $type (${list.size})")
                    list.forEach {
                        result.add("    $it")
                        issues++
                    }
                }
            }
        }
        result.forEach { println(it) }
        val total = allFine + issues
        val percentageIdentical = (allFine.toDouble() / total) / 100
        ChatUtils.clickableChat(
            "comparison done: ${percentageIdentical.roundTo(1)}% identical.\n" +
                "total: $total, all fine: $allFine, issues: $issues.\n" +
                "see console for more infos or click here to copy to clipboard.",
            onClick = {
                CoroutineSettings("put wiki npc comparison data to clipboard").launchCoroutine {
                    OSUtils.copyToClipboardAsync(result.joinToString("\n"))
                }
            },
        )
        ChatUtils.chat()
    }
}
