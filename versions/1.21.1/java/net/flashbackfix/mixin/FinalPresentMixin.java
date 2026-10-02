package net.flashbackfix.mixin;

import net.flashbackfix.ImguiPresentFix;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 Minecraft.runTick 层 bracket 主渲染的 blitToScreen(II) 调用，而不是注入
 * RenderTarget.blitToScreen 内部——Flashback 的 priority-800 mixin 在编辑器开启
 * 时会 cancel 掉 blitToScreen，注入其内部的 hook 无法可靠执行；runTick 自身的
 * 注入点不受该 cancel 影响（与 1.21.11 的 FinalBackbufferSampleMixin 同构）。
 *
 * BEFORE blitToScreen：重置本帧认领并把 imgui overlay 渲染到离屏 RT。关键收益：
 * 编辑器关闭帧（点导出后 isActiveInternal 翻转的 602 帧）的 imgui 停画发生在
 * blit **之前**，blitToScreen 执行时 ReplayUI.isActive() 已为 false——
 * MixinRenderTarget 不再接管、原版 blit 按真实窗口尺寸把游戏画面铺满 backbuffer，
 * imgui 区域不再依赖 swap 后的 backbuffer 残留（Android EGL 下残留不可靠，
 * 正是点导出切换瞬间 imgui 黑块的根源）。
 *
 * AFTER blitToScreen：把离屏 imgui 以 alpha blend 叠上 backbuffer（编辑器开启帧），
 * 随后执行推迟的主 RT resize，再进入 RenderSystem.flipFrame 完成 swap。
 * Flashback 自带的 afterMainBlit 也在同一 AFTER 点触发 drawOverlay，但其调用
 * 被 ReplayUIGuardMixin 的 claimDraw 掉，与本 hook 的先后顺序无关。
 */
@Mixin(Minecraft.class)
public abstract class FinalPresentMixin {

    private static final String BLIT =
            "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen(II)V";

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = BLIT))
    private void flashbackandroidfix$drawOverlayBeforeBlit(boolean bl, CallbackInfo ci) {
        ImguiPresentFix.drawBeforePresent();
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = BLIT, shift = At.Shift.AFTER))
    private void flashbackandroidfix$presentOverlayAfterBlit(boolean bl, CallbackInfo ci) {
        ImguiPresentFix.presentOverlay();
        ImguiPresentFix.applyPendingResize();
    }
}
