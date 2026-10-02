package net.flashbackfix;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;

/**
 * 黑闪修复的 pending 标记（1.21.1 / 1.21.4 旧渲染栈版）。
 *
 * <p>ReplayUI 的 transitionActiveState（编辑器开关）与 drawOverlayInternal
 * （帧区域变化）会直接调用 {@code Minecraft.resizeDisplay()}，它 destroy+create
 * 主 RT 的 buffers——而此刻本帧画面刚画在主 RT 上，紧接着的 blitToScreen 会
 * 拿到被清空的 RT，整帧闪黑（点导出/关编辑器瞬间即由此触发）。
 *
 * <p>ReplayUIGuardMixin 把这两处调用 redirect 到 {@link #requestResize()}，
 * 只做标记；等本帧画面经 blitToScreen 写入 backbuffer 之后，由
 * FlushDeferredResizeMixin 调 {@link #applyPendingResize()} 真正执行——
 * 此时重建主 RT 不会清掉即将 swap 上屏的内容，黑闪消失。
 *
 * <p>实现与 1.21.11 版 ImguiPresentFix 的 resize 段一致，尺寸防御也照抄
 * Flashback 原调用点（窗口尺寸为 0 或异常时跳过，避免非法 resize）。
 */
public final class FlashbackResizeFix {

    private static boolean pendingResize = false;

    /** ReplayUI 两处 resizeDisplay 的 redirect 落点：只打标记，不立即重建。 */
    public static void requestResize() {
        pendingResize = true;
    }

    /** blitToScreen 之后执行被推迟的主 RT 重建。 */
    public static void applyPendingResize() {
        if (!pendingResize) {
            return;
        }
        pendingResize = false;
        Minecraft minecraft = Minecraft.getInstance();
        Window window = minecraft.getWindow();
        if (window.getWidth() > 0 && window.getWidth() <= 16384
                && window.getHeight() > 0 && window.getHeight() <= 16384) {
            minecraft.resizeDisplay();
        }
    }

    private FlashbackResizeFix() {}
}
