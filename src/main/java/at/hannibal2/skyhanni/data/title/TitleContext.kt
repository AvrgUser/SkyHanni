package at.hannibal2.skyhanni.data.title

import at.hannibal2.skyhanni.config.core.config.Position
import at.hannibal2.skyhanni.data.GuiEditManager
import at.hannibal2.skyhanni.utils.ColorUtils
import at.hannibal2.skyhanni.utils.RenderUtils
import at.hannibal2.skyhanni.utils.SimpleTimeMark
import at.hannibal2.skyhanni.utils.SimpleTimeMark.Companion.farPast
import at.hannibal2.skyhanni.utils.SimpleTimeMark.Companion.now
import at.hannibal2.skyhanni.utils.compat.DrawContextUtils
import at.hannibal2.skyhanni.utils.compat.GuiScreenUtils
import at.hannibal2.skyhanni.utils.compat.MinecraftCompat
import at.hannibal2.skyhanni.utils.compat.SkyHanniGuiContainer
import at.hannibal2.skyhanni.utils.renderables.Renderable
import at.hannibal2.skyhanni.utils.renderables.RenderableUtils.renderXYAligned
import at.hannibal2.skyhanni.utils.renderables.container.VerticalContainerRenderable.Companion.vertical
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class TitleIntention(
    val displayName: String,
    val internalName: String,
)

open class TitleContext(
    private val titleSupplier: () -> Renderable,
    private val subtitleSupplier: (() -> Renderable)? = null,
    private var intention: TitleIntention? = null,
    var duration: Duration = 1.seconds,
    val weight: Double = 1.0,
    var discardOnWorldChange: Boolean = true,
) {
    constructor(
        title: Renderable,
        subtitle: Renderable? = null,
        intention: TitleIntention? = null,
        duration: Duration = 1.seconds,
        weight: Double = 1.0,
        discardOnWorldChange: Boolean = true,
    ) : this({ title }, subtitle?.let { { it } }, intention, duration, weight, discardOnWorldChange)

    var endTime: SimpleTimeMark? = null
    private var hasBeenReQueued: Boolean = false

    open val alive get() = endTime != null && (endTime?.isInPast() == false)

    open fun getTitleRenderable(): Renderable = titleSupplier()
    open fun getSubtitleRenderable(): Renderable? = subtitleSupplier?.invoke()

    open fun start() {
        if (endTime == null || endTime?.isInPast() == true) {
            endTime = now() + duration
        }
    }

    open fun stop() {
        endTime = farPast()
    }

    open fun processRequeue(): Boolean {
        if (hasBeenReQueued) return false
        hasBeenReQueued = true
        duration = endTime?.timeUntil() ?: Duration.ZERO
        return true
    }

    override fun equals(other: Any?): Boolean = this === other || other is TitleContext && this.dataEquivalent(other)
    override fun hashCode(): Int =
        titleSupplier.hashCode() * 31 + subtitleSupplier.hashCode() * 31 +
            duration.hashCode() * 31 + weight.hashCode()

    protected fun dataEquivalent(other: TitleContext): Boolean =
        duration == other.duration && weight == other.weight

    private fun TitleIntention.getPositionOrNull(location: TitleManager.TitleLocation): Position? {
        val intentionPosition = TitleManager.guiConfig.titleIntentionPositions[location]
        return intentionPosition?.get(this.internalName)
    }

    fun tryRenderGlobalTitle() {
        val intentionPosition = intention?.getPositionOrNull(TitleManager.TitleLocation.GLOBAL)
        val position = intentionPosition ?: TitleManager.guiConfig.titlePosition
        val guiWidth = GuiScreenUtils.scaledWindowWidth

        DrawContextUtils.pushPop {
            val mainRenderable = getTitleRenderable()
            val subtitleRenderable = getSubtitleRenderable()

            val targetRenderable = if (subtitleRenderable == null) mainRenderable
            else Renderable.vertical(
                mainRenderable,
                subtitleRenderable,
                horizontalAlign = RenderUtils.HorizontalAlignment.CENTER,
                verticalAlign = RenderUtils.VerticalAlignment.CENTER,
            )

            val renderableWidth = targetRenderable.width
            val renderableHeight = targetRenderable.height

            val translationX = (guiWidth - renderableWidth) / 2
            val translationY = position.y.takeIf { it >= 0 } ?: 100
            if (translationX != position.x || translationY != position.y) {
                position.set(Position(translationX, translationY, scale = position.scale))
            }

            DrawContextUtils.translate(translationX.toFloat(), translationY.toFloat())
            targetRenderable.renderXYAligned(0, 0, renderableWidth, renderableHeight)

            if (intentionPosition != null) {
                val qualifiedIntention = intention ?: return@pushPop
                val intentionFormat = "Title: ${qualifiedIntention.displayName}"
                GuiEditManager.add(intentionPosition, intentionFormat, targetRenderable.width, targetRenderable.height)
            } else {
                GuiEditManager.add(position, "Title", targetRenderable.width, targetRenderable.height)
            }
        }
    }

    fun tryRenderInventoryTitle() {
        val gui = MinecraftCompat.screen as? SkyHanniGuiContainer ?: return

        val mainRenderable = getTitleRenderable()
        val subtitleRenderable = getSubtitleRenderable()

        val targetRenderable = if (subtitleRenderable == null) mainRenderable
        else Renderable.vertical(
            mainRenderable,
            subtitleRenderable,
            horizontalAlign = RenderUtils.HorizontalAlignment.CENTER,
            verticalAlign = RenderUtils.VerticalAlignment.CENTER,
        )

        val translation = targetRenderable.height.toFloat() + 125f

        DrawContextUtils.pushPop {
            DrawContextUtils.translate(0f, -translation)
            Renderable.drawInsideRoundedRect(
                targetRenderable,
                ColorUtils.TRANSPARENT_COLOR,
                horizontalAlign = RenderUtils.HorizontalAlignment.CENTER,
                verticalAlign = RenderUtils.VerticalAlignment.CENTER,
            ).renderXYAligned(0, 125, gui.width, gui.height)

            DrawContextUtils.translate(0f, translation)
        }
    }

    override fun toString(): String = buildString {
        append("Title: [Renderable Component]\n")
        append("Duration: ${duration.inWholeSeconds}s\n")
        append("Weight: ${weight}\n")
        append("End Time: ${endTime?.timeUntil()?.inWholeSeconds ?: 0.0}s\n")
    }
}
