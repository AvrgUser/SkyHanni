package at.hannibal2.skyhanni.config.features.chat

import at.hannibal2.skyhanni.config.FeatureToggle
import at.hannibal2.skyhanni.utils.InputKeys
import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption
import io.github.notenoughupdates.moulconfig.annotations.SearchTag

class CopyChatConfig {
    @Expose
    @ConfigOption(
        name = "Copy Chat",
        desc = "Click a chat message with the keybind to copy it. Holding Shift will copy the message with " +
            "Shwords applied, and holding Ctrl will copy only one line.",
    )
    @SearchTag("control")
    @ConfigEditorBoolean
    @FeatureToggle
    var enabled: Boolean = true

    @Expose
    @ConfigOption(
        name = "Copy Chat Keybind",
        desc = "The keybind to use for copying chat messages.",
    )
    var keybind = InputKeys.MOUSE_BUTTON_RIGHT
}
