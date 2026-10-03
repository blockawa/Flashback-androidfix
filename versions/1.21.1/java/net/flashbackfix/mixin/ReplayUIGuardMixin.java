package net.flashbackfix.mixin;

import com.moulberry.flashback.editor.ui.ReplayUI;
import net.flashbackfix.ImguiPresentFix;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
 * 2. 推迟 resize（黑闪）：transitionActiveState 和 drawOverlayInternal 里的
 *    Minecraft.resizeDisplay() 会 destroy+create 主 RT 的 buffers，而此刻本帧
 *    画面刚画在主 RT 上，紧接着的 blitToScreen 会拿到被清空的 RT → 闪黑。
 *    这里 redirect 成 pending 标志，推迟到 FinalPresentMixin 的 AFTER hook
 *    （overlay present 之后）再真正执行。
 *
 * remap 说明：本类的注入点既有 Flashback 的 mod 方法（drawOverlay 等，mappings
 * 无条目、AP 保留原名），也有 MC 方法 resizeDisplay（必须 named→intermediary
 * 重映射），因此类级必须保持默认 remap=true；写成 false 会让 resizeDisplay 的
 * target 在运行时匹配不到、注入失败并崩溃。
 */
@Mixin(ReplayUI.class)
public abstract class ReplayUIGuardMixin {

    @Shadow
    private static boolean isActiveInternal() {
        throw new AssertionError("mixin shadow");
    }

    @Shadow
    private static void transitionActiveState(boolean active) {
        throw new AssertionError("mixin shadow");
    }

    @Inject(method = "drawOverlay()V", at = @At("HEAD"), cancellable = true)
    private static void flashbackandroidfix$skipDuplicate(CallbackInfo ci) {
        if (!ImguiPresentFix.claimDraw()) {
            ci.cancel();
        }
    }

    /**
     * 导出关闭态的抢先转移：点导出后 {@code isActiveInternal()} 立即翻 false，但原生
     * 要到 drawOverlay 中段的 {@code !isActiveInternal()} 分支才执行 transitionActiveState(false)；
     * 若当帧 screen 是 ProgressScreen / ReceivingLevelScreen（ExportJob 启动 seek 时 MC 会弹
     * 地形屏约 1-2 秒），drawOverlay 在更早的 screen 判断处就提前 return——状态机整个被截断：
     * activeLastFrame 保持 true，MixinRenderTarget 继续接管 blit（游戏只进视口矩形，呈左下角
     * 小窗），imgui 不画，视口外 backbuffer 无写入，Android 下 swap 残留不可靠即整屏黑。
     *
     * <p>这里在 HEAD 抢先完成转移，当帧 {@code isActive()} 即为 false，原版 blit 按窗口尺寸
     * 铺满，黑窗口不再出现。transitionActiveState 对同值调用直接 return（幂等），正常编辑器帧
     * {@code isActiveInternal()} 为 true 不触发；被 claimDraw cancel 的重复调用帧即使执行也幂等。
     */
    @Inject(method = "drawOverlay()V", at = @At("HEAD"))
    private static void flashbackandroidfix$earlyCloseTransition(CallbackInfo ci) {
        if (!isActiveInternal()) {
            transitionActiveState(false);
        }
    }

    @Redirect(
        method = {"transitionActiveState", "drawOverlayInternal"},
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;resizeDisplay()V"
        )
    )
    private static void flashbackandroidfix$deferResize(Minecraft minecraft) {
        ImguiPresentFix.requestResize();
    }
}
