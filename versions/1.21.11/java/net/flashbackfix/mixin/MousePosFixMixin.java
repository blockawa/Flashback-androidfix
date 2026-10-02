package net.flashbackfix.mixin;

import com.moulberry.flashback.editor.ui.CustomImGuiImplGlfw;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.exporting.AsyncFileDialogs;
import imgui.moulberry90.ImGuiIO;
import imgui.moulberry90.flag.ImGuiConfigFlags;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bug 1（交互失灵）：无效 MousePos 补位。
 *
 * <p>updateMousePosAndButtons 每帧先把 io.MousePos 重置为 -FLT_MAX，只有
 * mouseWindowPtr==窗口 或 GLFW_FOCUSED 才覆盖真实坐标。mouseWindowPtr 只由
 * cursor-enter(true) 设置、cursor-enter(false) 清零——setGrabbed 切
 * CURSOR_DISABLED 触发 leave 后没有机制补发 enter，双信号皆断时 MousePos 恒为
 * -FLT_MAX，imgui 认为鼠标不在屏幕上，hover/wantCaptureMouse 全部失效、点击无
 * 响应。TAIL 检测到无效值且各道门都放开时补一次 glfwGetCursorPos（同帧生效于
 * ImGui.newFrame 之前），坐标系与原逻辑同构（ViewportsEnable 时加窗口偏移）。
 * EDITOR_GRABBED/hasDialog/编辑器关闭时 allowImgui()=false，修复自动让位。
 *
 * <p>remap 保持 false：本类注入元数据与 @Shadow 只有原始类型描述符
 * （J/[D/[I）与 mod/第三方类（MouseHandledBy 经 imguiGlfw 链式调用），
 * 无 MC 类型，不需要也不应重映射。@Shadow 数组字段不带 final 以免未初始化
 * final 的编译限制，名称+描述符匹配不受 final 标志影响（26.1.2
 * ExportThumbnailTextureMixin 的 thumbnail 同款写法已实测运行）。
 */
@Mixin(value = CustomImGuiImplGlfw.class, remap = false)
public class MousePosFixMixin {

    @Shadow
    private long mainWindowPtr;
    @Shadow
    private double[] mouseX;
    @Shadow
    private double[] mouseY;
    @Shadow
    private int[] windowX;
    @Shadow
    private int[] windowY;

    @Inject(method = "updateMousePosAndButtons", at = @At("TAIL"), require = 0)
    private void flashbackandroidfix$repairMousePos(CallbackInfo ci) {
        ImGuiIO io = ReplayUI.getIO();
        if (io.getMousePosX() < -1.0E30f
                && ReplayUI.isActive()
                && !AsyncFileDialogs.hasDialog()
                && ReplayUI.imguiGlfw.getMouseHandledBy().allowImgui()) {
            GLFW.glfwGetCursorPos(this.mainWindowPtr, this.mouseX, this.mouseY);
            if (io.hasConfigFlags(ImGuiConfigFlags.ViewportsEnable)) {
                GLFW.glfwGetWindowPos(this.mainWindowPtr, this.windowX, this.windowY);
                io.setMousePos((float) this.mouseX[0] + this.windowX[0],
                        (float) this.mouseY[0] + this.windowY[0]);
            } else {
                io.setMousePos((float) this.mouseX[0], (float) this.mouseY[0]);
            }
        }
    }
}
