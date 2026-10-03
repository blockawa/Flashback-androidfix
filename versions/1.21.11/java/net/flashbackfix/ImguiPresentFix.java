package net.flashbackfix;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.editor.ui.ReplayUI;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

public final class ImguiPresentFix {

    private static boolean drawnThisFrame;
    private static RenderTarget compositeScreen;
    /** 原版 GL3 后端的离屏绘制目标：renderDrawData 画进来，AFTER hook 合成上屏。 */
    private static RenderTarget overlayScreen;
    /** 本帧 renderDrawData 是否真正执行过（与原版早退条件一致）。 */
    private static boolean overlayRendered;
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
     * 返回 GL3 后端应写入的离屏 FBO。取法与 Flashback SaveableFramebuffer
     * 一致：新栈 GlTexture 暴露的 getFbo 即其底层 GL 帧缓冲。
     * 离屏尚未创建时返回 0（防御：维持原行为而不是抛 NPE）。
     */
    public static int overlayFbo() {
        if (overlayScreen == null) {
            return 0;
        }
        GpuTexture texture = overlayScreen.getColorTexture();
        return ((GlTexture) texture).getFbo(
                ((GlDevice) RenderSystem.getDevice()).directStateAccess(), null);
    }

    /**
     * CustomImGuiImplGl3DelegateMixin 在 renderDrawData 入口（通过原版早退条件后）调用：
     * 按 imgui 帧缓冲尺寸准备离屏 RT（resize + 清成全透明）并绑定，
     * 同时置"本帧已渲染"标志。后续原版 setupRenderState 的绑 0 会被
     * redirect 回这里绑定的离屏 FBO。
     */
    public static void prepareOverlay(int fbWidth, int fbHeight) {
        overlayScreen = FixFramebuffers.resizeOrCreate(overlayScreen, fbWidth, fbHeight);
        FixFramebuffers.clear(overlayScreen, 0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, overlayFbo());
        overlayRendered = true;
    }

    /**
     * Runs at Minecraft.runTick immediately before the
     * mainRenderTarget.blitToScreen() call: claims this frame's single
     * drawOverlay call. The original GL3 backend renders the overlay into the
     * offscreen {@link #overlayScreen} (prepared at renderDrawData entry).
     * Flashback's afterMainBlit drawOverlay fires after the invoke and is
     * dropped by the claimDraw guard.
     */
    public static void drawBeforePresent(RenderTarget renderTarget) {
        resetFrame();
        overlayRendered = false;
        if (!RenderSystem.isOnRenderThread()) {
            return;
        }
        if (ReplayUI.isActive() && ReplayUI.imguiGlfw.isGrabbed()
                && GLFW.glfwGetMouseButton(Minecraft.getInstance().getWindow().handle(),
                        GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_RELEASE) {
            ReplayUI.imguiGlfw.ungrab();
        }
        // 绘制全程把传统 GL 的 FBO 绑定圈在 drawOverlay 内，结束后恢复原绑定
        int oldFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        try {
            ReplayUI.drawOverlay();
        } finally {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, oldFbo);
        }
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
        if (!ReplayUI.isActive() || !overlayRendered) {
            return null;
        }
        RenderTarget overlay = overlayScreen;
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
