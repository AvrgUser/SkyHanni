package at.hannibal2.skyhanni.features.misc

import at.hannibal2.skyhanni.SkyHanniMod
import at.hannibal2.skyhanni.api.event.HandleEvent
import at.hannibal2.skyhanni.data.title.TitleContext
import at.hannibal2.skyhanni.data.title.TitleManager
import at.hannibal2.skyhanni.events.ScoreboardUpdateEvent
import at.hannibal2.skyhanni.skyhannimodule.SkyHanniModule
import at.hannibal2.skyhanni.utils.RegexUtils.firstMatcher
import at.hannibal2.skyhanni.utils.StringUtils.removeColor
import at.hannibal2.skyhanni.utils.repopatterns.RepoPattern
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@SkyHanniModule
object ServerRestartTitle {

    private val config get() = SkyHanniMod.feature.misc
    private val patternGroup = RepoPattern.group("features.misc.serverrestart")
    private var timerTitleContext: TitleContext? = null

    /**
     * REGEX-TEST: Server closing: 03:11 m77A
     */
    private val restartingPattern by patternGroup.pattern(
        "time.colorless",
        "Server closing: (?<minutes>\\d+):(?<seconds>\\d+) ?.*",
    )

    /**
     * REGEX-TEST: Server closing: 03:11 m77A
     */
    val restartingGreedyPattern by patternGroup.pattern(
        "greedy.colorless",
        "Server closing.*",
    )

    @HandleEvent(onlyOnSkyblock = true)
    private fun onScoreboardEvent(event: ScoreboardUpdateEvent) {
        if (!config.serverRestartTitle) {
            timerTitleContext?.stop()
            timerTitleContext = null
            return
        }
        restartingPattern.firstMatcher(event.added.map { it.removeColor() }) {
            if (timerTitleContext?.alive == true) return@firstMatcher
            if (timerTitleContext?.alive == false) {
                timerTitleContext = null
            }
            val minutes = group("minutes").toInt().minutes
            val seconds = group("seconds").toInt().seconds
            val totalTime = minutes + seconds
            if (totalTime > 2.minutes && totalTime.inWholeSeconds % 30 != 0L) return@firstMatcher

            timerTitleContext = TitleManager.sendTimedTitle(
                titleTemplate = "§cServer Restart in §b%f",
                duration = totalTime,
                weight = -1.0,
            ) ?: timerTitleContext
        }
    }
}
