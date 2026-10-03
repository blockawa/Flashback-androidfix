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
    /** 诊断日志节流：各点位独立 5 秒窗口，防止黑屏时每帧刷日志。 */
    private static long lastDrawLogMs;
    private static long lastPrepLogMs;
    private static long lastCompLogMs;
    private static long lastEmptyLogMs;
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
        int fbo = overlayFbo();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        overlayRendered = true;
        // 诊断：renderDrawData 真正执行且通过原版早退条件的证据（绘制链第一环）
        long now = System.currentTimeMillis();
        if (now - lastPrepLogMs > 5000) {
            lastPrepLogMs = now;
            Flashback.LOGGER.info("[flashback-androidfix] overlay prepared {}x{} fbo={}", fbWidth, fbHeight, fbo);
        }
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
            // 诊断：整帧绘制被跳过的分支（低概率，仍节流记录）
            long now = System.currentTimeMillis();
            if (now - lastDrawLogMs > 5000) {
                lastDrawLogMs = now;
                Flashback.LOGGER.warn("[flashback-androidfix] drawBeforePresent skipped: not on render thread");
            }
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
        // 诊断：drawOverlay 返回但离屏未置备 = 内部早退（不活跃/进度屏/空帧）
        if (!overlayRendered) {
            long now = System.currentTimeMillis();
            if (now - lastDrawLogMs > 5000) {
                lastDrawLogMs = now;
                Flashback.LOGGER.warn("[flashback-androidfix] drawOverlay produced nothing: active={} screen={}",
                        ReplayUI.isActive(), Minecraft.getInstance().screen);
            }
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
            // 诊断：消费环节跳过的原因（区分"没画"与"画了没消费"）
            long now = System.currentTimeMillis();
            if (now - lastCompLogMs > 5000) {
                lastCompLogMs = now;
                Flashback.LOGGER.warn("[flashback-androidfix] composite skipped: active={} overlayRendered={}",
                        ReplayUI.isActive(), overlayRendered);
            }
            return null;
        }
        RenderTarget overlay = overlayScreen;
        if (overlay == null || overlay.getColorTextureView() == null) {
            // 诊断：画过但离屏 RT/视图缺失（矛盾态，说明 resize/创建出错）
            long now = System.currentTimeMillis();
            if (now - lastCompLogMs > 5000) {
                lastCompLogMs = now;
                Flashback.LOGGER.warn("[flashback-androidfix] composite skipped: overlay RT unavailable");
            }
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

            GpuTextureView result = compositeScreen.getColorTextureView();
            // 诊断：合成成功（随后立即 presentTexture 上屏）的证据
            long now = System.currentTimeMillis();
            if (now - lastCompLogMs > 5000) {
                lastCompLogMs = now;
                Flashback.LOGGER.info("[flashback-androidfix] composite built {}x{} -> presentTexture",
                        framebufferWidth, framebufferHeight);
            }
            return result;
        } catch (Throwable t) {
            Flashback.LOGGER.error("[flashback-androidfix] composite build failed", t);
            return null;
        }
    }

    /**
     * CustomImGuiImplGl3DelegateMixin 在 renderDrawData 早退（空帧/零尺寸）时调用：
     * 记录"renderDrawData 被调用但没有可画内容"的证据，与 prepareOverlay 的
     * 成功日志互补，用于把断点定位到具体哪一环。
     */
    public static void logEmptyDraw(int fbWidth, int fbHeight, int cmdLists) {
        long now = System.currentTimeMillis();
        if (now - lastEmptyLogMs > 5000) {
            lastEmptyLogMs = now;
            Flashback.LOGGER.warn("[flashback-androidfix] renderDrawData empty: {}x{} cmdLists={}",
                    fbWidth, fbHeight, cmdLists);
        }
    }
}
