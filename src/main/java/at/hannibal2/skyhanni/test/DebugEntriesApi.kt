package at.hannibal2.skyhanni.test

import at.hannibal2.skyhanni.SkyHanniMod
import at.hannibal2.skyhanni.api.event.HandleEvent
import at.hannibal2.skyhanni.skyhannimodule.SkyHanniModule
import at.hannibal2.skyhanni.utils.compat.appendWithColor
import at.hannibal2.skyhanni.utils.compat.componentBuilder
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer
import net.minecraft.client.gui.components.debug.DebugScreenEntry
import net.minecraft.resources.Identifier
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.LevelChunk
import org.jetbrains.annotations.ApiStatus

//? if < 26.4
// import net.minecraft.client.gui.components.debug.DebugScreenEntries
//?} else
import net.minecraft.client.gui.components.debug.DebugGroup

@SkyHanniModule
object DebugEntriesApi {
    private val debugEntries = mutableListOf<Pair<Identifier, DebugScreenEntry>>()

    //? if < 26.4 {
    /*@HandleEvent
    private fun onPreInitFinished() {
        for ((id, entry) in debugEntries) {
            DebugScreenEntries.register(id, entry)
        }
    }
    *///?} else {
    private val debugGroup = DebugGroup.Builder.titled(
        componentBuilder {
            appendWithColor(SkyHanniMod.MODID, ChatFormatting.YELLOW)
        }
    ).build()

    @JvmStatic
    @ApiStatus.Internal
    fun getAllDebugEntries(): List<Pair<Identifier, DebugScreenEntry>> {
        return debugEntries
    }
    //?}

    fun register(name: String, entry: DebugScreenEntry) {
        register(SkyHanniMod.id(name), entry)
    }

    fun register(id: Identifier, entry: DebugScreenEntry) {
        debugEntries += Pair(id, entry)
    }

    fun registerDebugScreenEntry(
        name: String,
        condition: () -> Boolean = { true },
        lineBuilder: MutableList<String>.() -> Unit,
    ): Pair<Identifier, DebugScreenEntry> {
        val id = SkyHanniMod.id(name)
        return Pair(
            id,
            object : DebugScreenEntry {
                override fun display(
                    displayer: DebugScreenDisplayer,
                    level: Level?,
                    clientChunk: LevelChunk?,
                    serverChunk: LevelChunk?,
                ) {
                    if (level == null || !condition()) return
                    displayer.addToGroup(
                        //? if < 26.4
                        //id,
                        //? else
                        debugGroup,
                        buildList(lineBuilder),
                    )
                }

                override fun isAllowed(reducedDebugInfo: Boolean) = true
            }
        )
    }
}
