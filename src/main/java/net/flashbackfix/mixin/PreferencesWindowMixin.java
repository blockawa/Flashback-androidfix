package net.flashbackfix.mixin;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.editor.ui.ImGuiHelper;
import com.moulberry.flashback.editor.ui.windows.PreferencesWindow;
import imgui.moulberry90.ImGui;
import net.flashbackfix.ExportPathUtil;
import net.flashbackfix.FixConfig;
import net.minecraft.client.resources.language.I18n;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在偏好设置窗口 "Exporting" 分节之后画导出文件夹选择器（路径 + 浏览 + 游戏内选择器）——
 * 从导出窗口挪到这里，让路径成为持久偏好而非每次导出都显示。
 *
 * <p>同时画界面大小设置：imgui 缩放滑块只落盘（{@link FixConfig#save()}）不立即生效，
 * 点「重新应用」按钮才调 {@link FixConfig#apply()} 并重建字体；滑块、重新应用、重置
 * 三个控件同一排（重新应用在重置左边），有未应用的改动时下一行显示「尚未应用」提示。
 *
 * <p>注入点："Keyframes" 分隔线（ordinal 1），即 "Exporting" 分节末尾。
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
        ExportPathUtil.renderPathUi(Flashback.getConfig());
        renderInterfaceSize();
    }

    private static void renderInterfaceSize() {
        FixConfig config = FixConfig.get();

        ImGuiHelper.separatorWithText(I18n.get("flashbackandroidfix.interface_size"));

        // 同排三控件：[滑块][重新应用][重置]，按钮固定宽度，滑块占满剩余宽度；
        // 两个按钮与滑块之间各一个 itemSpacing，宽度计算一并扣掉
        String scaleLabel = I18n.get("flashbackandroidfix.interface_scale");
        String applyLabel = I18n.get("flashbackandroidfix.interface_reload");
        String resetLabel = I18n.get("flashbackandroidfix.interface_reset");
        float labelWidth = ImGuiHelper.calcTextWidth(scaleLabel);
        float applyWidth = ImGuiHelper.calcTextWidth(applyLabel) + 16f;
        float resetWidth = ImGuiHelper.calcTextWidth(resetLabel) + 16f;
        float spacing = ImGui.getStyle().getItemSpacingX();

        float[] scale = {config.imguiScale};
        ImGui.setNextItemWidth(Math.max(80f,
                ImGui.getContentRegionAvailX() - labelWidth - applyWidth - resetWidth - spacing * 2));
        if (ImGui.sliderFloat(scaleLabel, scale, 0.25f, 4f)) {
            config.imguiScale = scale[0];
            config.save(); // 只落盘，点「重新应用」才生效
        }
        ImGuiHelper.tooltip(I18n.get("flashbackandroidfix.interface_tooltip"));

        ImGui.sameLine();
        if (ImGui.button(applyLabel, applyWidth, 0)) {
            config.apply();
        }

        ImGui.sameLine();
        if (ImGui.button(resetLabel, resetWidth, 0)) {
            config.imguiScale = 1.0f;
            config.save(); // 同样只落盘，需点「重新应用」生效
        }
        ImGuiHelper.tooltip(I18n.get("flashbackandroidfix.interface_reset_tooltip"));

        if (config.imguiScale != config.appliedScale()) {
            ImGui.textDisabled(I18n.get("flashbackandroidfix.interface_pending"));
        }
    }
}
