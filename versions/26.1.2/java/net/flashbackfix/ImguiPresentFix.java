package net.flashbackfix;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.FramebufferUtils;
import com.moulberry.flashback.WindowSizeTracker;
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
    /** 自建离屏 FBO 句柄（-1 = 尚未创建），见 {@link #overlayFbo()}。 */
    private static int overlayFboId = -1;
    /** FBO 上次挂接的裸 GL 纹理名：overlay RT resize 换纹理后据此重建。 */
    private static int overlayFboTexId = -1;
    private static boolean pendingResize;

    private ImguiPresentFix() {
    }

    /**
     * ReplayUI 的两处 Minecraft.resizeGui() 调用被 ReplayUIGuardMixin
     * redirect 到这里，只做标记不立即执行：resizeGui 会 destroy+create
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
            minecraft.resizeGui();
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
     * 返回 GL3 后端应写入的离屏 FBO。
     *
     * <p>26.1.2 的 GlDevice 是包私有类（1.21.11 为 public），mod 拿不到
     * GlTexture.getFbo 所需的 DirectStateAccess 实例，反射又因生产环境
     * intermediary 不 remap 字符串而不可用，故这里裸 GL 自建 FBO 挂到
     * overlay 的 color 纹理上——与 MC 内部 FBO 平行、互不干扰，imgui
     * 产出同样落进该纹理显存，供 buildComposite 合成时读取。
     *
     * <p>overlay RT resize 会换纹理，凭缓存的纹理名检测并在下次取用时
     * 重建；离屏尚未创建或纹理无效时返回 0（防御：维持原行为而不是抛 NPE）。
     */
    public static int overlayFbo() {
        if (overlayScreen == null) {
            return 0;
        }
        GpuTexture texture = overlayScreen.getColorTexture();
        if (!(texture instanceof GlTexture glTexture) || glTexture.isClosed()) {
            return 0;
        }
        int texId = glTexture.glId();
        if (texId <= 0) {
            return 0;
        }
        if (overlayFboId > 0 && overlayFboTexId == texId) {
            return overlayFboId;
        }
        // 首次创建或纹理已更换：重建 FBO（保存并恢复调用方的绑定，避免副作用）
        int previousFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        if (overlayFboId > 0) {
            GL30.glDeleteFramebuffers(overlayFboId);
            overlayFboId = -1;
        }
        int fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, texId, 0);
        if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
            // 挂接失败：删掉半成品、回退默认帧缓冲，宁可 overlay 不出也不给坏句柄
            GL30.glDeleteFramebuffers(fbo);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo);
            Flashback.LOGGER.warn("[flashback-androidfix] overlay FBO incomplete, falling back to default");
            return 0;
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo);
        overlayFboId = fbo;
        overlayFboTexId = texId;
        return fbo;
    }

    /**
     * CustomImGuiImplGl3DelegateMixin 在 renderDrawData 入口（通过原版早退条件后）调用：
     * 按 imgui 帧缓冲尺寸准备离屏 RT（resize + 清成全透明）并绑定，
     * 同时置"本帧已渲染"标志。后续原版 setupRenderState 的绑 0 会被
     * redirect 回这里绑定的离屏 FBO。
     */
    public static void prepareOverlay(int fbWidth, int fbHeight) {
        overlayScreen = FramebufferUtils.resizeOrCreateFramebuffer(
                overlayScreen, fbWidth, fbHeight, false);
        FramebufferUtils.clear(overlayScreen, 0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, overlayFbo());
        overlayRendered = true;
    }

    /**
     * Runs at Minecraft.renderFrame immediately before the
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
            int framebufferWidth = WindowSizeTracker.getWidth(window);
            int framebufferHeight = WindowSizeTracker.getHeight(window);

            compositeScreen = FramebufferUtils.resizeOrCreateFramebuffer(
                    compositeScreen, framebufferWidth, framebufferHeight, false);
            FramebufferUtils.clear(compositeScreen, 0);

            GpuTextureView gameView = self.getColorTextureView();
            if (gameView != null && ReplayUI.frameWidth > 1 && ReplayUI.frameHeight > 1) {
                float frameTop = (float) ReplayUI.frameY / ReplayUI.viewportSizeY;
                float frameLeft = (float) ReplayUI.frameX / ReplayUI.viewportSizeX;
                float frameWidth = (float) ReplayUI.frameWidth / ReplayUI.viewportSizeX;
                float frameHeight = (float) ReplayUI.frameHeight / ReplayUI.viewportSizeY;

                FramebufferUtils.blitTo(gameView, compositeScreen,
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
