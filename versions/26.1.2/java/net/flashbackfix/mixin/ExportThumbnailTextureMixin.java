package net.flashbackfix.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.moulberry.flashback.editor.ui.windows.ExportDoneWindow;
import net.flashbackfix.ImGuiB3DRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Flashback 0.43.3's export thumbnail id is a raw GL texture name
 * ({@code ((GlTexture)uploaded.getTexture()).glId()}), which the Gl3 backend binds
 * directly. The B3D renderer expects ids from its own 1-based registry instead,
 * so the finished-export dialog crashed with IndexOutOfBoundsException.
 *
 * <p>Mirrors Flashback 0.43.6's fix: return
 * {@code ReplayUI.imguiRenderer.getTextureId(uploaded.getTextureView())} via
 * {@link ImGuiB3DRenderer#getTextureId}.
 */
@Mixin(
    targets = "com.moulberry.flashback.editor.ui.windows.ExportDoneWindow$FinishedExportEntry",
    remap = false
)
public class ExportThumbnailTextureMixin {

    @Shadow
    private NativeImage thumbnail;

    @Shadow
    private DynamicTexture uploaded;

    @Inject(method = "getThumbnailTextureId", at = @At("HEAD"), cancellable = true)
    private void flashbackandroidfix$registryTextureId(CallbackInfoReturnable<Integer> cir) {
        if (this.thumbnail == null) {
            return;
        }

        if (this.uploaded == null) {
            this.uploaded = new DynamicTexture(() -> "flashback export thumbnail", this.thumbnail);
        }

        cir.setReturnValue((int) ImGuiB3DRenderer.get().getTextureId(this.uploaded.getTextureView()));
    }
}
