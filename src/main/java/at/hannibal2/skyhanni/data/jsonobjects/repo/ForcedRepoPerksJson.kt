package at.hannibal2.skyhanni.data.jsonobjects.repo

import at.hannibal2.skyhanni.data.Perk
import at.hannibal2.skyhanni.utils.SimpleTimeMark
import com.google.gson.annotations.Expose

data class ForcedRepoPerksJson(
    @Expose val perks: List<Perk>? = null,
    @Expose val timedPerks: List<TimedPerk>? = null,
)

data class TimedPerk(
    @Expose val perk: Perk,
    @Expose val start: Long,
    @Expose val end: Long,
) {
    val isActive: Boolean get() = isActiveAt(SimpleTimeMark.now().toMillis())

    fun isActiveAt(time: Long): Boolean = time in start..end
}
