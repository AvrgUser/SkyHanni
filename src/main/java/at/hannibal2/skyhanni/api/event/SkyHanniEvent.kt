package at.hannibal2.skyhanni.api.event

import at.hannibal2.skyhanni.utils.compat.DrawContextUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import tech.thatgravyboat.skyblockapi.api.events.base.EventBus
import tech.thatgravyboat.skyblockapi.api.events.base.SkyBlockEvent

/**
 * Use @[HandleEvent]
 */
abstract class SkyHanniEvent protected constructor(): SkyBlockEvent() {
    fun post() = prePost(onError = null)

    override fun post(bus: EventBus): Boolean = post().isCancelled

    fun post(onError: (Throwable) -> Unit = {}) = prePost(onError)

    private fun prePost(onError: ((Throwable) -> Unit)?): SkyHanniEvent = apply {
        (this as? Rendering)?.let { DrawContextUtils.setContext(it.context) }
        SkyHanniEvents.getEventHandler(javaClass).post(this, onError)
        if (this is Rendering) DrawContextUtils.clearContext()
    }

    typealias Cancellable = SkyBlockEvent.Cancellable

    interface Rendering {
        val context: GuiGraphicsExtractor
    }
}
