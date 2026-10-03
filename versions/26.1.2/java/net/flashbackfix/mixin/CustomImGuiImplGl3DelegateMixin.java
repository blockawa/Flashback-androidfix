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
 * 保留 Flashback 原版 GL3 后端渲染（替代旧的 26.2 B3D 移植方案），只做两处最小干预：
 *
 * <p>1. {@code setupRenderState} 里的 {@code glBindFramebuffer(GL_FRAMEBUFFER, 0)}
 * 无条件把绘制目标切到默认帧缓冲——在新栈（present 由 GpuDevice 的
 * CommandEncoder 管理）叠加 MobileGlues 下，这条绕过 RenderSystem 的裸
 * immediate-mode 路径整个失效，overlay 渲染不出来。这里 redirect 成 mod 的
 * 离屏 RT FBO，让原版绘制产出进离屏，随后由 FinalBackbufferSampleMixin 的
 * AFTER hook 合成上屏。
 *
 * <p>2. {@code renderDrawData} 入口按与原版早退一致的条件准备离屏
 * （resize/clear/绑定）并置"本帧已渲染"标志，供
 * {@link ImguiPresentFix#buildComposite} 判断本帧是否有 overlay 可合成。
 *
 * <p>init/newFrame/updateFontsTexture/renderDrawData 主体全部走 Flashback 原版。
 *
 * <p>remap=false：目标均为 Flashback 自身方法与 LWJGL 调用，无 MC 映射条目。
 */
@Mixin(value = CustomImGuiImplGl3.class, remap = false)
public class CustomImGuiImplGl3DelegateMixin {

    /**
     * 把原版的"绑 FBO 0"改绑到 mod 离屏 RT。setupRenderState 全方法只有这一处
     * glBindFramebuffer 调用（renderDrawData 保存/恢复段的那次不在本方法内）。
     */
    @Redirect(
        method = "setupRenderState",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30;glBindFramebuffer(II)V")
    )
    private void flashbackandroidfix$bindOffscreen(int target, int framebuffer) {
        GL30.glBindFramebuffer(target, ImguiPresentFix.overlayFbo());
    }

    @Inject(method = "renderDrawData", at = @At("HEAD"))
    private void flashbackandroidfix$prepareOverlay(ImDrawData drawData, CallbackInfo ci) {
        // 与原版 renderDrawData 的早退条件保持一致，避免空帧把离屏标记置上
        int fbWidth = (int) (drawData.getDisplaySizeX() * drawData.getFramebufferScaleX());
        int fbHeight = (int) (drawData.getDisplaySizeY() * drawData.getFramebufferScaleY());
        if (fbWidth > 0 && fbHeight > 0 && drawData.getCmdListsCount() > 0) {
            ImguiPresentFix.prepareOverlay(fbWidth, fbHeight);
        }
    }
}
