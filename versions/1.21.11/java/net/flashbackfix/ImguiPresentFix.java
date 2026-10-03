package net.flashbackfix;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.editor.ui.ReplayUI;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public final class ImguiPresentFix {

    private static boolean drawnThisFrame;
    private static RenderTarget compositeScreen;
    private static boolean pendingResize;

    private ImguiPresentFix() {
    }

    /**
     * ReplayUI 的两处 Minecraft.resizeDisplay() 调用被 ReplayUIGuardMixin
     * redirect 到这里，只做标记不立即执行：resizeDisplay 会 destroy+create
     * 主 RT 的 buffers，而此时本帧画面刚画在主 RT 上，紧随其后的 blitToScreen
     * 会拿到被清空的 RT，导致编辑器打开/帧区域变化的一瞬黑闪。
     */
    public static void requestResize() {
        pendingResize = true;
    }

    /**
     * 在 FinalBackbufferSampleMixin 的 AFTER hook 里、presentTexture 之后调用：
     * 本帧内容已经上屏（backbuffer 不受主 RT 重建影响），此时再真正执行 resize。
     * 窗口尺寸无效（0 或超过 16384）时跳过，与 transitionActiveState 原有防护一致。
     */
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

    public static void resetFrame() {
        drawnThisFrame = false;
    }

    public static boolean claimDraw() {
        if (drawnThisFrame) {
            return false;
        }
        drawnThisFrame = true;
        return true;
    }

    /**
     * Runs at Minecraft.runTick immediately before the
     * mainRenderTarget.blitToScreen() call: claims this frame's single
     * drawOverlay call and lets the B3D renderer produce the offscreen
     * imgui target. Flashback's afterMainBlit drawOverlay fires after the
     * invoke and is dropped by the claimDraw guard.
     */
    public static void drawBeforePresent(RenderTarget renderTarget) {
        resetFrame();
        if (!RenderSystem.isOnRenderThread()) {
            return;
        }
        ImGuiB3DRenderer.lastComposite = null;
        if (ReplayUI.isActive() && ReplayUI.imguiGlfw.isGrabbed()
                && GLFW.glfwGetMouseButton(Minecraft.getInstance().getWindow().handle(),
                        GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_RELEASE) {
            ReplayUI.imguiGlfw.ungrab();
        }
        ReplayUI.drawOverlay();
    }

    /**
     * Builds the screen-sized composite (cropped game frame + imgui overlay) and
     * returns the texture view to present, or null to fall through to the normal
     * present path.
     */
    public static GpuTextureView buildComposite(RenderTarget self) {
        if (self != Minecraft.getInstance().getMainRenderTarget()) {
            return null;
        }
        if (!ReplayUI.isActive()) {
            return null;
        }
        RenderTarget overlay = ImGuiB3DRenderer.lastComposite;
        if (overlay == null || overlay.getColorTextureView() == null) {
            return null;
        }
        try {
            Window window = Minecraft.getInstance().getWindow();
            int framebufferWidth = FixFramebuffers.framebufferWidth(window);
            int framebufferHeight = FixFramebuffers.framebufferHeight(window);

            compositeScreen = FixFramebuffers.resizeOrCreate(compositeScreen, framebufferWidth, framebufferHeight);
            FixFramebuffers.clear(compositeScreen, 0);

            GpuTextureView gameView = self.getColorTextureView();
            if (gameView != null && ReplayUI.frameWidth > 1 && ReplayUI.frameHeight > 1) {
                float frameTop = (float) ReplayUI.frameY / ReplayUI.viewportSizeY;
                float frameLeft = (float) ReplayUI.frameX / ReplayUI.viewportSizeX;
                float frameWidth = (float) ReplayUI.frameWidth / ReplayUI.viewportSizeX;
                float frameHeight = (float) ReplayUI.frameHeight / ReplayUI.viewportSizeY;

                CompositeBlit.blitCrop(gameView, compositeScreen,
                        framebufferWidth, framebufferHeight,
                        frameLeft, frameTop,
                        frameLeft + frameWidth, frameTop + frameHeight);
            }

            CompositeBlit.blitOverlay(overlay.getColorTextureView(), compositeScreen,
                    framebufferWidth, framebufferHeight);

            return compositeScreen.getColorTextureView();
        } catch (Throwable t) {
            Flashback.LOGGER.error("[flashback-androidfix] composite build failed", t);
            return null;
        }
    }
}
