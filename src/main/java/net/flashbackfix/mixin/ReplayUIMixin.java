package net.flashbackfix.mixin;

import com.moulberry.flashback.editor.ui.ReplayUI;
import imgui.moulberry90.ImGui;
import net.flashbackfix.FixConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把 {@link FixConfig#appliedScale()} 的界面缩放推入 {@link ReplayUI#newGlobalScale}，
 * 在 Flashback 自身的缩放处理之前执行。
 *
 * <p>那段逻辑自带量化、0.25-4 钳位、赋值 {@code globalScale}，且仅像素字体尺寸跨档时才
 * 重建字体——所以缩放变更一帧内生效（含按需字体重建），无需改 Flashback 源码。偏好设置里
 * 滑块一动 {@code apply()} 即更新 appliedScale，下一帧这里推的就是新值（实时生效）。
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
