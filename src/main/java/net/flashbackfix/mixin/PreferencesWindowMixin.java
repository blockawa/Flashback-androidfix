package net.flashbackfix.mixin;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.editor.ui.ImGuiHelper;
import com.moulberry.flashback.editor.ui.windows.PreferencesWindow;
import imgui.moulberry90.ImGui;
import net.flashbackfix.ExportPathUi;
import net.flashbackfix.FixConfig;
import net.minecraft.client.resources.language.I18n;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the export folder field (path + Browse + in-game picker) inside the Preferences
 * window, right after the "Exporting" section - moved here from the export window so the
 * path is a persistent preference instead of something shown on every export.
 *
 * <p>Also draws the interface size setting (imgui UI scale slider, applied on demand via
 * {@code ReplayUIMixin}).
 *
 * <p>Injection point: the "Keyframes" separator (ordinal 1), i.e. the end of "Exporting".
 */
@Mixin(value = PreferencesWindow.class, remap = false)
public class PreferencesWindowMixin {

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lcom/moulberry/flashback/editor/ui/ImGuiHelper;separatorWithText(Ljava/lang/String;)V",
            ordinal = 1,
            shift = At.Shift.BEFORE
        )
    )
    private static void flashbackandroidfix$renderExportLocation(CallbackInfo ci) {
        ExportPathUi.render(Flashback.getConfig());
        renderInterfaceSize();
    }

    private static void renderInterfaceSize() {
        FixConfig config = FixConfig.get();

        ImGuiHelper.separatorWithText(I18n.get("flashbackandroidfix.interface_size"));

        float[] scale = {config.imguiScale};
        if (ImGui.sliderFloat(I18n.get("flashbackandroidfix.interface_scale"), scale, 0.25f, 4f)) {
            config.imguiScale = scale[0];
            config.save();
        }
        ImGuiHelper.tooltip(I18n.get("flashbackandroidfix.interface_tooltip"));

        if (config.imguiScale != config.appliedScale()) {
            ImGui.textDisabled(I18n.get("flashbackandroidfix.interface_pending"));
        }

        if (ImGui.button(I18n.get("flashbackandroidfix.interface_reload"))) {
            config.apply();
        }
        ImGui.sameLine();
        if (ImGui.button(I18n.get("flashbackandroidfix.interface_reset"))) {
            config.imguiScale = 1.0f;
            config.save();
        }
        ImGuiHelper.tooltip(I18n.get("flashbackandroidfix.interface_reset_tooltip"));
    }
}
