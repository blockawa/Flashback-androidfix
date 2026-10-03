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
import imgui.moulberry90.ImDrawData;
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
    /** 读回诊断节流：10 秒一次（glReadPixels 有同步开销）。 */
    private static long lastReadLogMs;
    /** prepareOverlay 记录的本帧离屏尺寸：读回诊断用，规避 RenderTarget 尺寸 API 的版本差异。 */
    private static int overlayW;
    private static int overlayH;
    /** composite 读回诊断节流：10 秒一次（与 overlay 读回独立计时）。 */
    private static long lastCompReadLogMs;
    /** 屏幕（FBO 0）读回诊断节流：10 秒一次，验证 presentTexture 的 blit 是否真的到达 backbuffer。 */
    private static long lastScreenReadLogMs;
    /** 字体纹理读回诊断节流：10 秒一次，区分"纹理上传坏"与"采样/顶点属性坏"。 */
    private static long lastFontReadLogMs;
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
        overlayW = fbWidth;
        overlayH = fbHeight;
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

            // 诊断：读回 overlay 产出（10 秒一次），区分"没画上"与"合成失效"
            logOverlayReadback();

            // 针对性修复：带 depth attachment（useDepth=true），对齐 Flashback partial
            // present 成功路径的 tempRT——无 depth 的 RT 作新栈 renderPass 目标时，
            // MobileGlues（GLES 底层）严格拒绝而 zink 容忍，导致合成静默失效
            compositeScreen = FramebufferUtils.resizeOrCreateFramebuffer(
                    compositeScreen, framebufferWidth, framebufferHeight, true);
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
            // 诊断：读回合成结果（10 秒一次），对照 overlay 区分"合成没写进"与"上屏失效"
            logCompositeReadback(compositeScreen, framebufferWidth, framebufferHeight);
            return result;
        } catch (Throwable t) {
            Flashback.LOGGER.error("[flashback-androidfix] composite build failed", t);
            return null;
        }
    }

    /**
     * 诊断：读回 overlay 离屏内容（10 秒一次），统计 alpha 非零样本，
     * 一次性区分"imgui 绘制无产出（裸 immediate-mode 失效方向）"与
     * "产出正常但合成/采样环节失效"。
     */
    private static void logOverlayReadback() {
        long now = System.currentTimeMillis();
        if (now - lastReadLogMs < 10000) {
            return;
        }
        lastReadLogMs = now;
        int w = overlayW;
        int h = overlayH;
        int fbo = overlayFbo();
        if (w <= 0 || h <= 0 || fbo <= 0) {
            Flashback.LOGGER.warn("[flashback-androidfix] overlay readback skipped: {}x{} fbo={}", w, h, fbo);
            return;
        }
        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            java.nio.ByteBuffer px = org.lwjgl.BufferUtils.createByteBuffer(4);
            int nonZero = 0;
            int rgbLit = 0;
            int sampled = 0;
            // 8 条横带、每带 64 点：覆盖全屏高度分布，避免只采到空白区
            for (int band = 0; band < 8; band++) {
                int y = h * (band * 2 + 1) / 16;
                for (int i = 0; i < 64; i++) {
                    int x = w * (i * 2 + 1) / 128;
                    px.clear();
                    GL11.glReadPixels(x, y, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
                    sampled++;
                    if (px.get(3) != 0) {
                        nonZero++;
                    }
                    if ((px.get(0) & 0xFF) != 0 || (px.get(1) & 0xFF) != 0 || (px.get(2) & 0xFF) != 0) {
                        rgbLit++;
                    }
                }
            }
            Flashback.LOGGER.info("[flashback-androidfix] overlay readback: {}/{} non-transparent, {}/{} rgb-lit",
                    nonZero, sampled, rgbLit, sampled);
        } catch (Throwable t) {
            Flashback.LOGGER.warn("[flashback-androidfix] overlay readback failed: {}", t.toString());
        } finally {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        }
    }

    /**
     * 诊断：读回 composite 合成结果（10 秒一次），统计非透明/不透明样本，
     * 与 overlay 读回对照，区分"合成没写进纹理"与"presentTexture 上屏失效"。
     * 26.1.2 的 GlDevice 包私有拿不到 {@code getFbo}，改用临时 FBO 挂
     * composite 纹理，读完即删（10 秒一次开销可忽略）。
     */
    private static void logCompositeReadback(RenderTarget composite, int w, int h) {
        long now = System.currentTimeMillis();
        if (now - lastCompReadLogMs < 10000 || w <= 0 || h <= 0) {
            return;
        }
        lastCompReadLogMs = now;
        GpuTexture texture = composite.getColorTexture();
        if (!(texture instanceof GlTexture glTexture) || glTexture.isClosed()) {
            Flashback.LOGGER.warn("[flashback-androidfix] composite readback skipped: bad texture");
            return;
        }
        int texId = glTexture.glId();
        if (texId <= 0) {
            Flashback.LOGGER.warn("[flashback-androidfix] composite readback skipped: texId={}", texId);
            return;
        }
        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int fbo = GL30.glGenFramebuffers();
        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, texId, 0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                Flashback.LOGGER.warn("[flashback-androidfix] composite readback skipped: temp FBO incomplete");
                return;
            }
            java.nio.ByteBuffer px = org.lwjgl.BufferUtils.createByteBuffer(4);
            int nonZero = 0;
            int opaque = 0;
            int rgbLit = 0;
            int sampled = 0;
            for (int band = 0; band < 8; band++) {
                int y = h * (band * 2 + 1) / 16;
                for (int i = 0; i < 64; i++) {
                    int x = w * (i * 2 + 1) / 128;
                    px.clear();
                    GL11.glReadPixels(x, y, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
                    sampled++;
                    int a = px.get(3) & 0xFF;
                    if (a != 0) {
                        nonZero++;
                    }
                    if (a >= 200) {
                        opaque++;
                    }
                    if ((px.get(0) & 0xFF) != 0 || (px.get(1) & 0xFF) != 0 || (px.get(2) & 0xFF) != 0) {
                        rgbLit++;
                    }
                }
            }
            Flashback.LOGGER.info("[flashback-androidfix] composite readback: {}/{} non-transparent, {}/{} opaque, {}/{} rgb-lit",
                    nonZero, sampled, opaque, sampled, rgbLit, sampled);
        } catch (Throwable t) {
            Flashback.LOGGER.warn("[flashback-androidfix] composite readback failed: {}", t.toString());
        } finally {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
            GL30.glDeleteFramebuffers(fbo);
        }
    }

    /**
     * 诊断：presentTexture 之后读回屏幕（FBO 0，10 秒一次），验证 blit 是否真的写进
     * backbuffer。与 composite 读回对照判读：composite 高而非透明而 screen 明显低
     * = blit 静默失败（MobileGlues 直通 GLES 的严格格式检查）；两者都高 = 上屏成功，
     * 断点在更后端。scissor 此前已被 presentTexture 内部 disable，读回不受裁剪。
     */
    public static void logScreenReadback() {
        int w = overlayW;
        int h = overlayH;
        long now = System.currentTimeMillis();
        if (now - lastScreenReadLogMs < 10000 || w <= 0 || h <= 0) {
            return;
        }
        lastScreenReadLogMs = now;
        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            java.nio.ByteBuffer px = org.lwjgl.BufferUtils.createByteBuffer(4);
            int nonZero = 0;
            int opaque = 0;
            int rgbLit = 0;
            int sampled = 0;
            for (int band = 0; band < 8; band++) {
                int y = h * (band * 2 + 1) / 16;
                for (int i = 0; i < 64; i++) {
                    int x = w * (i * 2 + 1) / 128;
                    px.clear();
                    GL11.glReadPixels(x, y, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
                    sampled++;
                    int a = px.get(3) & 0xFF;
                    if (a != 0) {
                        nonZero++;
                    }
                    if (a >= 200) {
                        opaque++;
                    }
                    if ((px.get(0) & 0xFF) != 0 || (px.get(1) & 0xFF) != 0 || (px.get(2) & 0xFF) != 0) {
                        rgbLit++;
                    }
                }
            }
            Flashback.LOGGER.info("[flashback-androidfix] screen readback: {}/{} non-transparent, {}/{} opaque, {}/{} rgb-lit",
                    nonZero, sampled, opaque, sampled, rgbLit, sampled);
        } catch (Throwable t) {
            Flashback.LOGGER.warn("[flashback-androidfix] screen readback failed: {}", t.toString());
        } finally {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        }
    }

    /**
     * 诊断：读回 imgui 字体纹理数据（10 秒一次），区分"纹理上传坏（RGB 黑）"与
     * "纹理数据正常但采样/顶点属性环节坏"。纹理 id 从 draw data 首个 cmd 取
     * （ImFontAtlas 未暴露 getter）。走临时 FBO + glReadPixels 路径（与 composite
     * 读回同款、在 MobileGlues 上已实证可用），规避 glGetTexImage 在 GLES 后端的
     * 实现差异。
     */
    public static void logFontTextureReadback(ImDrawData drawData) {
        long now = System.currentTimeMillis();
        if (now - lastFontReadLogMs < 10000) {
            return;
        }
        if (drawData.getCmdListsCount() <= 0 || drawData.getCmdListCmdBufferSize(0) <= 0) {
            return;
        }
        lastFontReadLogMs = now;
        long texId = drawData.getCmdListCmdBufferTextureId(0, 0);
        if (texId <= 0) {
            Flashback.LOGGER.warn("[flashback-androidfix] font texture readback skipped: texId={}", texId);
            return;
        }
        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int fbo = GL30.glGenFramebuffers();
        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, (int) texId, 0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                Flashback.LOGGER.warn("[flashback-androidfix] font texture readback skipped: FBO incomplete");
                return;
            }
            int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            java.nio.ByteBuffer px = org.lwjgl.BufferUtils.createByteBuffer(4);
            int rgbLit = 0;
            int alphaNonZero = 0;
            int sampled = 0;
            for (int band = 0; band < 8; band++) {
                int y = h * (band * 2 + 1) / 16;
                for (int i = 0; i < 64; i++) {
                    int x = w * (i * 2 + 1) / 128;
                    px.clear();
                    GL11.glReadPixels(x, y, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
                    sampled++;
                    if ((px.get(0) & 0xFF) != 0 || (px.get(1) & 0xFF) != 0 || (px.get(2) & 0xFF) != 0) {
                        rgbLit++;
                    }
                    if ((px.get(3) & 0xFF) != 0) {
                        alphaNonZero++;
                    }
                }
            }
            Flashback.LOGGER.info("[flashback-androidfix] font texture readback: {}x{} {}/{} rgb-lit, {}/{} alpha",
                    w, h, rgbLit, sampled, alphaNonZero, sampled);
        } catch (Throwable t) {
            Flashback.LOGGER.warn("[flashback-androidfix] font texture readback failed: {}", t.toString());
        } finally {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
            GL30.glDeleteFramebuffers(fbo);
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
