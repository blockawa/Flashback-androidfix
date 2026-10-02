package net.flashbackfix.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import com.moulberry.flashback.editor.ui.CustomImGuiImplGl3;
import imgui.moulberry90.ImDrawData;
import net.flashbackfix.ImguiPresentFix;
import org.lwjgl.opengl.GL11;
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
            // 每帧先把离屏清成透明再让 imgui 画：视口区域 imgui 不写像素，
            // 不清就残留纹理初始值（alpha 未定义），presentOverlay 的 alpha
            // blend 会拿它盖住 backbuffer 上的游戏帧——表现为视口灰屏。
            // colorMask 全开防 alpha 位被上一段渲染关掉（imgui 输出的 alpha
            // 要写进离屏，blend 才能正确透出游戏帧）；scissor 暂关，否则
            // clear 被上一帧的裁剪框限制，随后 renderDrawData 会重设裁剪框。
            GlStateManager._colorMask(true, true, true, true);
            boolean scissorWasEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glClearColor(0f, 0f, 0f, 0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            if (scissorWasEnabled) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
            }
        } else {
            // 离屏从未创建（窗口尺寸无效帧 ensure 早退），按原指令绑 backbuffer
            GL30.glBindFramebuffer(target, framebuffer);
        }
    }
}
