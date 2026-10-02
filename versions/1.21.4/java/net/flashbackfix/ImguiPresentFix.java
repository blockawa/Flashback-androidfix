package net.flashbackfix;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.moulberry.flashback.editor.ui.ReplayUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CompiledShaderProgram;
import net.minecraft.client.renderer.CoreShaders;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.util.Objects;

/**
 * 老栈（1.21.1 / 1.21.4）版 imgui 离屏 present 架构，对应 1.21.11 版同名类。
 *
 * <p>目标与 1.21.11 一致：imgui 不再直接画 backbuffer，而是渲到离屏 RT、
 * 在主 blitToScreen 之后 blend 上屏——编辑器关闭帧（drawOverlay 提前 return、
 * 本帧没画 imgui）backbuffer 由原版全屏 blit 的游戏画面填满，不再依赖 swap
 * 后 backbuffer 残留（Android EGL 下残留不可靠，正是点导出切换瞬间 imgui
 * 黑块的根源）。
 *
 * <p>与 1.21.11 的实现差异（老栈没有 blaze3d 新 API）：
 * <ul>
 *   <li>renderer 不重写：复用 Flashback 自带的 CustomImGuiImplGl3（MobileGlues
 *       下老栈 Gl3 可用），只把它 setupRenderState 里的 glBindFramebuffer(0)
 *       redirect 到离屏 FBO（Gl3OffscreenMixin）——因此也不需要 1.21.11 的
 *       textureId 注册表 / ExportThumbnail / Gl3Delegate 那三件套；</li>
 *   <li>present 不走 presentTexture：Flashback 的 MixinRenderTarget 已经把
 *       游戏帧画到 frame 区域（编辑器开启）或全屏（原版 blit），本类只需把
 *       离屏 imgui 以 alpha blend 叠上去；</li>
 *   <li>crop/CompositeBlit 不需要——游戏帧位置由 Flashback 的 blit 接管保证。</li>
 * </ul>
 */
public final class ImguiPresentFix {

    private static boolean drawnThisFrame = false;
    /** 本帧 drawOverlay 是否真的走到 Gl3 渲染（drawOverlay 提前 return 时为 false）。 */
    private static boolean frameRendered = false;
    private static boolean pendingResize = false;

    /** 离屏 imgui RT：尺寸跟随 imgui 的 fbWidth/fbHeight，经 Gl3OffscreenMixin 惰性创建。 */
    private static TextureTarget offscreen = null;

    private ImguiPresentFix() {}

    /**
     * ReplayUI 的两处 Minecraft.resizeDisplay() 调用被 ReplayUIGuardMixin
     * redirect 到这里，只做标记不立即执行：resizeDisplay 会 destroy+create
     * 主 RT 的 buffers，而此刻本帧画面刚画在主 RT 上，紧随其后的 blitToScreen
     * 会拿到被清空的 RT，导致编辑器开关/帧区域变化一瞬的黑闪。
     */
    public static void requestResize() {
        pendingResize = true;
    }

    /**
     * 在 FinalPresentMixin 的 AFTER hook 里、overlay present 之后调用：
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

    /** 每帧只认领一次 drawOverlay：BEFORE hook 认领，Flashback afterMainBlit 的第二次调用被 cancel。 */
    public static boolean claimDraw() {
        if (drawnThisFrame) {
            return false;
        }
        drawnThisFrame = true;
        return true;
    }

    /**
     * FinalPresentMixin 的 BEFORE hook（runTick 的 blitToScreen 之前）调用：
     * 认领本帧 drawOverlay 并先在离屏 RT 上完成 imgui 构建与渲染。
     * 编辑器关闭帧里 drawOverlayInternal 会提前 return（imgui 停画），
     * frameRendered 保持 false，本帧 present 跳过、由原版全屏 blit 填满 backbuffer。
     */
    public static void drawBeforePresent() {
        resetFrame();
        frameRendered = false;
        if (!RenderSystem.isOnRenderThread()) {
            return;
        }
        if (ReplayUI.isActive() && ReplayUI.imguiGlfw.isGrabbed()
                && GLFW.glfwGetMouseButton(Minecraft.getInstance().getWindow().getWindow(),
                        GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_RELEASE) {
            ReplayUI.imguiGlfw.ungrab();
        }
        ReplayUI.drawOverlay();
    }

    /**
     * Gl3OffscreenMixin 在 CustomImGuiImplGl3.setupRenderState HEAD 调用：
     * 确保离屏 RT 尺寸等于本帧 imgui 的像素尺寸（fbWidth/fbHeight），
     * 返回值仅作诊断，真正的 FBO 绑定经 offscreenFbo() 完成。
     */
    public static void ensureOffscreen(int fbWidth, int fbHeight) {
        if (fbWidth <= 0 || fbHeight <= 0) {
            return;
        }
        // 离屏创建失败直接向上抛出暴露问题——不做静默回退（回退机制已移除）
        if (offscreen == null || offscreen.width != fbWidth || offscreen.height != fbHeight) {
            if (offscreen != null) {
                offscreen.destroyBuffers();
            }
            offscreen = new TextureTarget(fbWidth, fbHeight, false);
        }
        frameRendered = true;
    }

    /** Gl3OffscreenMixin redirect glBindFramebuffer(GL_FRAMEBUFFER, 0) 时读取；0 仅出现在窗口尺寸无效帧（离屏从未创建），按原指令绑 backbuffer。 */
    public static int offscreenFbo() {
        return offscreen != null ? offscreen.frameBufferId : 0;
    }

    /**
     * FinalPresentMixin 的 AFTER hook 调用（紧跟 Flashback afterMainBlit 之后）：
     * 把离屏 imgui 以 alpha blend 叠到 backbuffer 上，紧接 updateDisplay 完成 swap。
     *
     * <p>状态管理与上屏方式照抄 Flashback MixinRenderTarget 的 blit 接管实现
     * （MC 官方 BLIT_SCREEN shader 路径），仅补 imgui 需要的标准 alpha blend。
     */
    public static void presentOverlay() {
        if (!frameRendered || !ReplayUI.isActive()) {
            return;
        }
        RenderSystem.assertOnRenderThread();
        Window window = Minecraft.getInstance().getWindow();
        int[] fbWidth = new int[1];
        int[] fbHeight = new int[1];
        GLFW.glfwGetFramebufferSize(window.getWindow(), fbWidth, fbHeight);
        if (fbWidth[0] <= 0 || fbHeight[0] <= 0) {
            return;
        }

        GlStateManager._colorMask(true, true, true, false);
        GlStateManager._disableDepthTest();
        GlStateManager._depthMask(false);
        GlStateManager._viewport(0, 0, fbWidth[0], fbHeight[0]);
        GlStateManager._enableBlend();
        GlStateManager._blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);

        CompiledShaderProgram shaderInstance = Objects.requireNonNull(
                RenderSystem.setShader(CoreShaders.BLIT_SCREEN), "Blit shader not loaded");
        shaderInstance.bindSampler("InSampler", offscreen.getColorTextureId());
        shaderInstance.apply();

        BufferBuilder bufferBuilder = RenderSystem.renderThreadTesselator()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLIT_SCREEN);
        bufferBuilder.addVertex(0.0f, 0.0f, 0.0f);
        bufferBuilder.addVertex(1.0f, 0.0f, 0.0f);
        bufferBuilder.addVertex(1.0f, 1.0f, 0.0f);
        bufferBuilder.addVertex(0.0f, 1.0f, 0.0f);
        BufferUploader.draw(bufferBuilder.buildOrThrow());
        shaderInstance.clear();

        GlStateManager._disableBlend();
        GlStateManager._depthMask(true);
        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._enableDepthTest();
    }
}
