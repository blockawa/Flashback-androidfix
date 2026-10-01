package net.flashbackfix.mixin;

import com.moulberry.flashback.editor.ui.ReplayUI;
import imgui.moulberry90.ImGui;
import net.flashbackfix.FixConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pushes the interface scale confirmed with the Reload button into {@link ReplayUI#newGlobalScale}
 * before Flashback's own scale handling runs.
 *
 * <p>That block quantizes the value, clamps it to 0.25-4, assigns {@code globalScale} and
 * rebuilds the fonts when the pixel font size changes - so applying the setting takes effect in
 * one frame, including font regeneration, without touching Flashback sources. Values edited in
 * the Preferences window are not pushed here until Reload is pressed.
 */
@Mixin(value = ReplayUI.class, remap = false)
public class ReplayUIMixin {

    @Inject(
        method = "drawOverlayInternal",
        at = @At(
            value = "INVOKE",
            target = "Limgui/moulberry90/ImGui;isAnyMouseDown()Z",
            shift = At.Shift.BEFORE
        )
    )
    private static void flashbackandroidfix$applyConfiguredScale(CallbackInfo ci) {
        ReplayUI.newGlobalScale = FixConfig.get().appliedScale();
    }
}
