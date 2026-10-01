package net.flashbackfix.mixin;

import com.moulberry.flashback.editor.ui.windows.ExportDoneWindow;
import net.flashbackfix.ImGuiB3DRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Flashback 0.39.x's export thumbnail id is a raw GL texture name
 * ({@code ((GlTexture)uploaded.getTexture()).glId()}), which the Gl3 backend binds
 * directly. The B3D renderer expects ids from its own 1-based registry instead,
 * so the finished-export dialog would render the wrong texture.
 *
 * <p>Mirrors Flashback 0.43.6's fix: return
 * {@code ImGuiB3DRenderer.get().getTextureId(uploaded.getTextureView())}.
 *
 * <p>Injects on RETURN rather than HEAD: the vanilla body is what creates
 * {@code uploaded}, and the 1.21.11 {@code thumbnail} field is final, so there
 * is no need to shadow it.
 */
@Mixin(
    targets = "com.moulberry.flashback.editor.ui.windows.ExportDoneWindow$FinishedExportEntry",
    remap = false
)
public class ExportThumbnailTextureMixin {

    @Shadow
    private DynamicTexture uploaded;

    @Inject(method = "getThumbnailTextureId", at = @At("RETURN"), cancellable = true)
    private void flashbackandroidfix$registryTextureId(CallbackInfoReturnable<Integer> cir) {
        DynamicTexture texture = this.uploaded;
        if (texture == null) {
            return;
        }

        cir.setReturnValue((int) ImGuiB3DRenderer.get().getTextureId(texture.getTextureView()));
    }
}
