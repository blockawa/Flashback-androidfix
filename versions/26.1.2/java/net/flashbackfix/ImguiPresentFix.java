package net.flashbackfix;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.FramebufferUtils;
import com.moulberry.flashback.WindowSizeTracker;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.exporting.AsyncFileDialogs;
import imgui.moulberry90.ImGuiIO;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public final class ImguiPresentFix {

    private static boolean drawnThisFrame;
    private static RenderTarget compositeScreen;
    private static boolean pendingResize;

    /** Bug 1 诊断开关：输入失灵定位完成后，连同相关日志一起删除。 */
    public static final boolean INPUT_DEBUG = true;

    private static String lastInputState = "";
    private static long lastInputLogTime;

    private ImguiPresentFix() {
    }

    /**
     * 供 InputDebugMixin 在输入事件日志里附带当前的门状态（上一帧快照）。
     */
    public static String inputStateSummary() {
        return lastInputState;
    }

    public static void logInput(String message) {
        if (INPUT_DEBUG) {
            Flashback.LOGGER.info("[flashback-androidfix][input] {}", message);
        }
    }

    /** Bug 1 诊断：updateMousePosAndButtons 处理前的原始鼠标状态（HEAD 记录）。 */
    private static float recRawMouseX;
    private static float recRawMouseY;
    private static boolean recFocused;
    private static boolean recMouseOnWin;
    private static int recCursorMode;
    /** 本帧 MousePos 修复是否触发（TAIL 记录）。 */
    private static boolean recRepaired;

    public static void recordMouseState(float rawX, float rawY, boolean focused,
                                        boolean mouseOnWin, int cursorMode) {
        if (!INPUT_DEBUG) {
            return;
        }
        recRawMouseX = rawX;
        recRawMouseY = rawY;
        recFocused = focused;
        recMouseOnWin = mouseOnWin;
        recCursorMode = cursorMode;
    }

    public static void recordRepaired(boolean repaired) {
        if (!INPUT_DEBUG) {
            return;
        }
        recRepaired = repaired;
    }

    /**
     * Bug 1 诊断：每帧快照输入链路的各道门（isActive/hasDialog/grabbed/
     * handledBy/wantCapture*）。离散状态变化时立刻打一条，否则每 10 秒
     * 打一条心跳（附鼠标坐标）证明渲染循环与日志链路活着。
     */
    private static void snapshotInputState() {
        if (!INPUT_DEBUG) {
            return;
        }
        try {
            // 注意：isActive=false 时不读 ImGuiIO（getIO() 在未初始化时会触发
            // ReplayUI.init()，而 LoadingOverlay 期间 drawOverlay 会早退不 init，
            // 不能替它做）。getMouseHandledBy() 在 !isActive 时短路返回 GAME，
            // 不会触到 IO，安全。isActive=true 时 init 必然已完成。
            boolean active = ReplayUI.isActive();
            StringBuilder state = new StringBuilder()
                    .append("active=").append(active)
                    .append(" hasDialog=").append(AsyncFileDialogs.hasDialog())
                    .append(" grabbed=").append(ReplayUI.imguiGlfw.isGrabbed())
                    .append(" handledBy=").append(ReplayUI.imguiGlfw.getMouseHandledBy());
            float mouseX = 0;
            float mouseY = 0;
            if (active) {
                ImGuiIO io = ReplayUI.getIO();
                state.append(" wantMouse=").append(io.getWantCaptureMouse())
                        .append(" wantKey=").append(io.getWantCaptureKeyboard())
                        .append(" wantText=").append(io.getWantTextInput());
                mouseX = io.getMousePosX();
                mouseY = io.getMousePosY();
            }
            state.append(" rawValid=").append(recRawMouseX > -1.0E30f)
                    .append(" mouseOnWin=").append(recMouseOnWin)
                    .append(" focused=").append(recFocused)
                    .append(" cursorMode=").append(recCursorMode)
                    .append(" fixed=").append(recRepaired);
            long now = System.currentTimeMillis();
            if (!state.toString().equals(lastInputState)) {
                lastInputState = state.toString();
                lastInputLogTime = now;
                Flashback.LOGGER.info("[flashback-androidfix][input] state {}", lastInputState);
            } else if (now - lastInputLogTime >= 10_000) {
                lastInputLogTime = now;
                Flashback.LOGGER.info("[flashback-androidfix][input] heartbeat {} mouse=({},{}) rawMouse=({},{})",
                        lastInputState, mouseX, mouseY, recRawMouseX, recRawMouseY);
            }
        } catch (Throwable t) {
            Flashback.LOGGER.error("[flashback-androidfix][input] snapshot failed", t);
        }
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
     * Runs at Minecraft.renderFrame immediately before the
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
        snapshotInputState();
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
