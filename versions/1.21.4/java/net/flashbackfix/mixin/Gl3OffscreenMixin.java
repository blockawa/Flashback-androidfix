package net.flashbackfix.mixin;

import com.moulberry.flashback.editor.ui.CustomImGuiImplGl3;
import imgui.moulberry90.ImDrawData;
import net.flashbackfix.ImguiPresentFix;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 老栈版的"Gl3 → 离屏"路由，等价于 1.21.11 的 CustomImGuiImplGl3DelegateMixin
 * + ImGuiB3DRenderer 组合，但复用 Flashback 自带的 Gl3 renderer（老栈
 * MobileGlues 下它可用，无需重写 400 行 renderer）：
 *
 * 1. setupRenderState HEAD：按本帧 imgui 像素尺寸（fbWidth/fbHeight）惰性
 *    创建/重建离屏 RT，并标记本帧 frameRendered=true（presentOverlay 的依据）。
 *    离屏创建失败时回退原路径（imgui 直接画 backbuffer），present 跳过。
 *
 * 2. redirect setupRenderState 里的 glBindFramebuffer(GL_FRAMEBUFFER, 0)：
 *    原代码无条件绑 backbuffer，这里改绑离屏 FBO，让 imgui 渲进离屏 RT。
 *    renderDrawData 进入时保存的 FBO 与退出时的恢复逻辑不受影响——drawData
 *    渲染期间被替换为离屏，函数结束自动恢复原绑定。
 *
 * 因 Gl3 直接使用真实 GL 纹理 id，1.21.11 所需的 textureId 注册表与
 * ExportThumbnailTextureMixin 在老栈均不需要。
 *
 * remap=false：CustomImGuiImplGl3 是 Flashback 类（AP 保留原名），方法内的
 * LWJGL 调用也不参与 MC 重映射。
 */
@Mixin(value = CustomImGuiImplGl3.class, remap = false)
public class Gl3OffscreenMixin {

    @Inject(method = "setupRenderState", at = @At("HEAD"))
    private void flashbackandroidfix$ensureOffscreen(ImDrawData drawData, int fbWidth, int fbHeight,
                                                     int gVertexArrayObject, CallbackInfo ci) {
        ImguiPresentFix.ensureOffscreen(fbWidth, fbHeight);
    }

    @Redirect(
        method = "setupRenderState",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/opengl/GL30;glBindFramebuffer(II)V",
            ordinal = 0
        )
    )
    private void flashbackandroidfix$bindOffscreenFramebuffer(int target, int framebuffer) {
        int offscreenFbo = ImguiPresentFix.offscreenFbo();
        if (offscreenFbo != 0) {
            GL30.glBindFramebuffer(target, offscreenFbo);
        } else {
            // 离屏未就绪（创建失败），保持原行为画 backbuffer，present 会跳过
            GL30.glBindFramebuffer(target, framebuffer);
        }
    }
}
