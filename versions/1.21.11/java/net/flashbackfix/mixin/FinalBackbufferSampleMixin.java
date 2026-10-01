package net.flashbackfix.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.flashbackfix.ImguiPresentFix;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives the imgui composite at the Minecraft.runTick level, bracketing the
 * mainRenderTarget.blitToScreen() call instead of injecting into
 * RenderTarget.blitToScreen itself. Flashback's priority-800 mixin cancels
 * blitToScreen entirely while the editor is active, so nothing injected inside
 * blitToScreen can run reliably; runTick's own injection points cannot be
 * cancelled that way.
 *
 * BEFORE blitToScreen: reset the per-frame claim and draw the imgui overlay
 * into its offscreen B3D target (Flashback's afterMainBlit drawOverlay, which
 * fires AFTER the invoke, is dropped by the claimDraw guard).
 *
 * AFTER blitToScreen: whatever blitToScreen managed to present (Flashback's
 * partial game present or the vanilla present) is overwritten here with the
 * composite, immediately before RenderSystem.flipFrame swaps buffers.
 */
@Mixin(Minecraft.class)
public abstract class FinalBackbufferSampleMixin {

    private static final String BLIT =
            "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen()V";

    @Inject(method = "runTick", at = @At(
            value = "INVOKE",
            target = BLIT))
    private void flashbackandroidfix$drawOverlayBeforeBlit(boolean advanceGameTime, CallbackInfo ci) {
        ImguiPresentFix.drawBeforePresent(Minecraft.getInstance().getMainRenderTarget());
    }

    @Inject(method = "runTick", at = @At(
            value = "INVOKE",
            target = BLIT,
            shift = At.Shift.AFTER))
    private void flashbackandroidfix$presentCompositeAfterBlit(boolean advanceGameTime, CallbackInfo ci) {
        RenderTarget self = Minecraft.getInstance().getMainRenderTarget();
        GpuTextureView composite = ImguiPresentFix.buildComposite(self);
        if (composite != null) {
            RenderSystem.getDevice().createCommandEncoder().presentTexture(composite);
        }
        // 本帧已上屏，现在才执行 ReplayUI 推迟的主 RT resize，避免黑闪
        ImguiPresentFix.applyPendingResize();
    }
}
