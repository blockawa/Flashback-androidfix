package net.flashbackfix.mixin;

import com.moulberry.flashback.editor.ui.CustomImGuiImplGl3;
import imgui.moulberry90.ImDrawData;
import net.flashbackfix.ImGuiB3DRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ReplayUI 26.1 only touches init/newFrame/updateFontsTexture/renderDrawData on
 * the legacy GL3 backend. Cancel all four and route them to the B3D renderer.
 */
@Mixin(value = CustomImGuiImplGl3.class, remap = false)
public class CustomImGuiImplGl3DelegateMixin {

    @Inject(method = "init(Ljava/lang/String;)Z", at = @At("HEAD"), cancellable = true)
    private void flashbackandroidfix$b3dInit(String glslVersion, CallbackInfoReturnable<Boolean> cir) {
        ImGuiB3DRenderer.get().init();
        cir.setReturnValue(true);
    }

    @Inject(method = "newFrame()V", at = @At("HEAD"), cancellable = true)
    private void flashbackandroidfix$b3dNewFrame(CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "updateFontsTexture()V", at = @At("HEAD"), cancellable = true)
    private void flashbackandroidfix$b3dUpdateFontsTexture(CallbackInfo ci) {
        ImGuiB3DRenderer.get().updateFontsTexture();
        ci.cancel();
    }

    @Inject(method = "renderDrawData(Limgui/moulberry90/ImDrawData;)V", at = @At("HEAD"), cancellable = true)
    private void flashbackandroidfix$b3dRenderDrawData(ImDrawData drawData, CallbackInfo ci) {
        ImGuiB3DRenderer.get().renderDrawData(drawData);
        ci.cancel();
    }
}
