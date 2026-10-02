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
 * <p>同时画界面大小设置：imgui 缩放滑块，**改动即实时生效**（滑块值一变立刻调
 * {@link FixConfig#apply()} 落盘并更新 appliedScale，下一帧经 {@code ReplayUIMixin} 应用），
 * 重置按钮与滑块同排、固定宽度贴最右（同导出路径行的浏览按钮布局）。
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

        // 布局同导出路径行：控件占满剩余宽度，按钮固定宽度贴最右；
        // sliderFloat 的可见 label 挤在同一行，宽度计算需一并扣掉
        String scaleLabel = I18n.get("flashbackandroidfix.interface_scale");
        String resetLabel = I18n.get("flashbackandroidfix.interface_reset");
        float labelWidth = ImGuiHelper.calcTextWidth(scaleLabel);
        float resetWidth = ImGuiHelper.calcTextWidth(resetLabel) + 16f;
        float spacing = ImGui.getStyle().getItemSpacingX();

        float[] scale = {config.imguiScale};
        ImGui.setNextItemWidth(Math.max(80f,
                ImGui.getContentRegionAvailX() - labelWidth - resetWidth - spacing));
        if (ImGui.sliderFloat(scaleLabel, scale, 0.25f, 4f)) {
            config.imguiScale = scale[0];
            config.apply(); // 实时：落盘 + appliedScale 更新，下一帧 ReplayUI 即收到新值
        }
        ImGuiHelper.tooltip(I18n.get("flashbackandroidfix.interface_tooltip"));

        ImGui.sameLine();
        if (ImGui.button(resetLabel, resetWidth, 0)) {
            config.imguiScale = 1.0f;
            config.apply(); // 重置同样即时生效
        }
        ImGuiHelper.tooltip(I18n.get("flashbackandroidfix.interface_reset_tooltip"));
    }
}
