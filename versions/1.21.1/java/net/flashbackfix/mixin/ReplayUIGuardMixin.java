package net.flashbackfix.mixin;

import com.moulberry.flashback.editor.ui.ReplayUI;
import net.flashbackfix.FlashbackResizeFix;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 黑闪修复前半段（1.21.1 / 1.21.4 旧渲染栈版）：把 ReplayUI 里两处
 * {@code Minecraft.resizeDisplay()} 调用 redirect 成 pending 标记——
 *
 * <ol>
 *   <li>{@code transitionActiveState}：编辑器开/关时重算游戏窗口尺寸；</li>
 *   <li>{@code drawOverlayInternal}：每帧末尾检测帧区域（视口 frame）变化。</li>
 * </ol>
 *
 * <p>原实现此刻 resizeDisplay 会 destroy+create 主 RT，本帧已画好的画面被清空、
 * 紧接的 blitToScreen 上屏黑帧（点导出/关编辑器瞬间的黑即源于此）。改为标记后，
 * 真正的重建推迟到本帧 blitToScreen 之后，由 FlushDeferredResizeMixin 执行。
 *
 * <p>remap 说明：{@code Minecraft.resizeDisplay} 是 MC 方法（named→intermediary
 * 必须重映射），{@code transitionActiveState}/{@code drawOverlayInternal} 是
 * Flashback 方法（mappings 无条目、AP 保留原名）——类级保持默认 remap=true，
 * 写成 false 会让 resizeDisplay 的 target 运行时匹配不到、注入失败并崩溃。
 */
@Mixin(ReplayUI.class)
public class ReplayUIGuardMixin {

    @Redirect(
        method = {"transitionActiveState", "drawOverlayInternal"},
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;resizeDisplay()V"
        )
    )
    private static void flashbackandroidfix$deferResize(Minecraft minecraft) {
        FlashbackResizeFix.requestResize();
    }
}
