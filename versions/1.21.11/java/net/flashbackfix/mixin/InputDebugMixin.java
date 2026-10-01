package net.flashbackfix.mixin;

import com.moulberry.flashback.editor.ui.CustomImGuiImplGlfw;
import net.flashbackfix.ImguiPresentFix;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bug 1（交互失灵）诊断日志：在 CustomImGuiImplGlfw 的输入入口记录"事件是否
 * 到达了 imgui 后端"，并附上 ImguiPresentFix 的门状态快照（active/hasDialog/
 * grabbed/handledBy/wantCapture*）。
 *
 * 判读方法：
 * - 有 mouseButton/key 日志但游戏没反应 → 门把事件拦了（看快照哪一项为拦）；
 * - 没有任何 mouseButton/key 日志但你确实在点/按键 → 事件根本没到 GLFW 回调
 *   （上游被拦，比如 screen 层或触摸转鼠标链路）；
 * - 有 setGrabbed/ungrab 日志 → 记录谁在何时抢/放鼠标。
 *
 * require=0：诊断代码宁可缺日志也不能崩游戏。诊断完成后整类删除。
 */
@Mixin(value = CustomImGuiImplGlfw.class, remap = false)
public class InputDebugMixin {

    @Inject(method = "mouseButtonCallback", at = @At("HEAD"), require = 0)
    private void flashbackandroidfix$logMouseButton(long windowId, int button, int action, int mods,
                                                    CallbackInfo ci) {
        ImguiPresentFix.logInput("mouseButton window=" + windowId + " button=" + button
                + " action=" + action + " mods=" + mods
                + " | " + ImguiPresentFix.inputStateSummary());
    }

    @Inject(method = "keyCallback", at = @At("HEAD"), require = 0)
    private void flashbackandroidfix$logKey(long windowId, int key, int scancode, int action, int mods,
                                            CallbackInfo ci) {
        ImguiPresentFix.logInput("key window=" + windowId + " key=" + key
                + " scancode=" + scancode + " action=" + action + " mods=" + mods
                + " | " + ImguiPresentFix.inputStateSummary());
    }

    @Inject(method = "setGrabbed", at = @At("HEAD"), require = 0)
    private void flashbackandroidfix$logSetGrabbed(boolean passthroughToGame, int grabLinkedKey,
                                                   boolean releaseGrabOnUp, double x, double y,
                                                   CallbackInfo ci) {
        ImguiPresentFix.logInput("setGrabbed passthrough=" + passthroughToGame
                + " linkedKey=" + grabLinkedKey + " releaseOnUp=" + releaseGrabOnUp
                + " pos=(" + x + "," + y + ")");
    }

    @Inject(method = "ungrab", at = @At("HEAD"), require = 0)
    private void flashbackandroidfix$logUngrab(CallbackInfo ci) {
        ImguiPresentFix.logInput("ungrab");
    }
}
