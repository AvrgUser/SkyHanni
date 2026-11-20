package at.hannibal2.skyhanni.api.event

import at.hannibal2.skyhanni.api.event.EventListeners.Listener
import at.hannibal2.skyhanni.data.IslandType
import at.hannibal2.skyhanni.utils.SkyBlockUtils

/**
 * All listeners of one event handler, sorted once by priority (stable, so registration order is kept within a priority).
 * Per-island lists are only computed when a snapshot is rebuilt, see [EventHandler].
 */
class ListenerCollection(
    listeners: List<Listener>,
) {

    private val sorted: List<Listener> = listeners.sortedBy { it.priority }

    fun forState(state: Int): List<Listener> = sorted.filter { it.isActiveIn(state) }

    companion object {

        const val OUTSIDE = 0

        /**
         * Offset applied to island ordinals because state 0
         * is reserved for the outside-SkyBlock state.
         */
        private const val ISLAND_OFFSET = 1
        private val STATE_COUNT = IslandType.entries.size + ISLAND_OFFSET

        fun getCurrentStateIndex(): Int {
            if (!SkyBlockUtils.inSkyBlock) return OUTSIDE
            return SkyBlockUtils.currentIsland.ordinal + ISLAND_OFFSET
        }

        /**
         * One flag per state (outside SkyBlock, then every island), true if a listener with [options] is active there.
         */
        fun createStateMask(options: HandleEvent): BooleanArray {
            val mask = BooleanArray(STATE_COUNT)
            val islands = getIslands(options)

            if (islands.isEmpty()) {
                val first = if (options.onlyOnSkyblock) ISLAND_OFFSET else OUTSIDE
                for (state in first until STATE_COUNT) mask[state] = true
            } else {
                for (island in islands) mask[island.ordinal + ISLAND_OFFSET] = true
            }
            return mask
        }

        private fun getIslands(options: HandleEvent): Set<IslandType> {
            val islandTypes = mutableSetOf<IslandType>()

            options.onlyOnIsland
                .takeUnless { it == IslandType.ANY }
                ?.let(islandTypes::add)

            islandTypes += options.onlyOnIslands

            options.onlyOnIslandTypeTag.forEach { tag -> islandTypes += tag.getTypes() }

            return islandTypes
        }
    }
}
