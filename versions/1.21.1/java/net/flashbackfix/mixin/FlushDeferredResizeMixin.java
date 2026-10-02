package net.flashbackfix.mixin;

import net.flashbackfix.FlashbackResizeFix;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 黑闪修复后半段（1.21.1 / 1.21.4 旧渲染栈版）：在 Minecraft.runTick 的
 * 主 RT {@code blitToScreen(II)} 之后执行 ReplayUI 推迟的 resize——
 *
 * <p>此刻本帧画面已由 blitToScreen 写入 backbuffer，重建主 RT（destroy+create
 * 只动 RT 自身的 FBO/纹理，不碰默认帧缓冲）不会清掉即将 swap 上屏的内容；
 * 紧随其后的 updateDisplay 完成 swap，新 RT 从下帧生效，黑闪消失。
 *
 * <p>priority=900：同注入点上 Flashback 自带的 afterMainBlit（默认 1000）先跑、
 * imgui 先画完，本 mixin 后跑，保证"全部绘制完成才重建 RT"的顺序。
 *
 * <p>与 1.21.11 版 FinalBackbufferSampleMixin 的差异：老栈不挪 imgui 绘制位置
 * （不涉及 B3D composite），只做 flush；drawOverlay 仍走 Flashback 原版
 * afterMainBlit 钩子。
 */
@Mixin(value = Minecraft.class, priority = 900)
public abstract class FlushDeferredResizeMixin {

    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen(II)V",
            shift = At.Shift.AFTER
        )
    )
    private void flashbackandroidfix$flushDeferredResize(boolean advanceGameTime, CallbackInfo ci) {
        FlashbackResizeFix.applyPendingResize();
    }
}
