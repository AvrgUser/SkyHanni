package at.hannibal2.skyhanni.mixins.transformers;

//? if >= 26.4 {
import at.hannibal2.skyhanni.test.DebugEntriesApi;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.core.Registry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DebugScreenEntries.class)
public class MixinDebugScreenEntries {
    @Inject(
        method = "bootstrap",
        at = @At("TAIL")
    )
    private static void bootstrap(Registry<DebugScreenEntry> registry, CallbackInfoReturnable<Object> cir) {
        var entries = DebugEntriesApi.getAllDebugEntries();
        for (var entry : entries) {
            var id = entry.getFirst();
            var debugEntry = entry.getSecond();
            Registry.register(registry, id, debugEntry);
        }
    }
}

//?}
