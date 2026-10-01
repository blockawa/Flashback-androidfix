package net.flashbackfix.mixin;

import com.moulberry.flashback.editor.ui.ReplayUI;
import net.flashbackfix.ImguiPresentFix;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * drawOverlay 生命周期守卫，两件事：
 *
 * 1. 防重复绘制：Flashback 自带的 afterMainBlit 钩子也会调 drawOverlay，而本
 *    mod 把绘制挪到了 blitToScreen 之前——claimDraw() 每帧认领一次，后到的调用
 *    被 cancel，保证每帧只画一遍。
 *
 * 2. Bug 2（黑闪）deferred resize：transitionActiveState 和 drawOverlayInternal
 *    里的 Minecraft.resizeGui() 会 destroy+create 主 RT 的 buffers，而此刻本帧
 *    画面刚画在主 RT 上，紧接着的 blitToScreen 会拿到被清空的 RT → 闪黑。这里
 *    redirect 成 pending 标志，推迟到 FinalBackbufferSampleMixin 的 AFTER hook
 *    （presentTexture 之后）再真正执行。
 *
 * remap 说明：本类的注入点既有 Flashback 的 mod 方法（drawOverlay 等，mappings
 * 无条目、AP 保留原名），也有 MC 方法 resizeGui（必须 named→intermediary 重映射），
 * 因此类级必须保持默认 remap=true；写成 false 会让 resizeGui 的 target 在运行时
 * 匹配不到、注入失败并崩溃。
 */
@Mixin(ReplayUI.class)
public class ReplayUIGuardMixin {

    @Inject(method = "drawOverlay()V", at = @At("HEAD"), cancellable = true)
    private static void flashbackandroidfix$skipDuplicate(CallbackInfo ci) {
        if (!ImguiPresentFix.claimDraw()) {
            ci.cancel();
        }
    }

    @Redirect(
        method = {"transitionActiveState", "drawOverlayInternal"},
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;resizeGui()V"
        )
    )
    private static void flashbackandroidfix$deferResize(Minecraft minecraft) {
        ImguiPresentFix.requestResize();
    }
}
