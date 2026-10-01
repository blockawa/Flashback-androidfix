package net.flashbackfix.mixin;

import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.exporting.SaveableFramebuffer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;

/**
 * Replaces the PBO-only pixel download in {@link SaveableFramebuffer}.
 *
 * <p>The original implementation read through a pixel pack buffer and threw when
 * {@code glMapBuffer} came back empty, which is what happens on the Android GL
 * translation layers this mod runs on (ANGLE/MobileGlues): mapping a pixel pack
 * buffer is unavailable there, so the very first exported frame killed the game
 * with {@code IllegalStateException: OpenGL error occurred while mapping buffer}.
 *
 * <p>Both download methods are replaced at HEAD. When the mapping fails we log
 * the GL error code once, permanently degrade this framebuffer to a synchronous
 * {@code glReadPixels} into a resident off-heap buffer, and keep exporting. The
 * degraded read runs inside the start phase, i.e. at the same instant the PBO
 * path snapshots the frame, so every later frame still captures the right image.
 * The single frame whose mapping failed re-reads at finish time, which may
 * already show the next frame - one blemished frame instead of a crash.
 */
@Mixin(value = SaveableFramebuffer.class, remap = false)
public class SaveableFramebufferMixin {

    @Shadow
    private int pboId;

    @Shadow
    private boolean isDownloading;

    @Unique
    private int flashbackandroidfix$fboId = 0;

    @Unique
    private boolean flashbackandroidfix$pboBroken = false;

    @Unique
    private @Nullable ByteBuffer flashbackandroidfix$syncBuffer;

    @Inject(
        method = "startDownload",
        at = @At("HEAD"),
        cancellable = true
    )
    private void flashbackandroidfix$startDownload(GpuTexture gpuTexture, int width, int height, CallbackInfo ci) {
        if (this.isDownloading) {
            throw new IllegalStateException("Can't start downloading while already downloading");
        }
        this.isDownloading = true;

        int fbo = ((GlTexture) gpuTexture).getFbo(((GlDevice) RenderSystem.getDevice()).directStateAccess(), null);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        this.flashbackandroidfix$fboId = fbo;

        GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 1);
        GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);

        if (this.flashbackandroidfix$pboBroken) {
            flashbackandroidfix$ensureSyncBuffer(width, height);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, this.flashbackandroidfix$syncBuffer);
        } else {
            if (this.pboId == -1) {
                this.pboId = GL30C.glGenBuffers();

                GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, this.pboId);
                GL30C.glBufferData(GL30C.GL_PIXEL_PACK_BUFFER, (long) width * height * 4, GL30C.GL_STREAM_READ);
                GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, 0);
            }

            GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, this.pboId);
            GL30C.glReadPixels(0, 0, width, height, GL30C.GL_RGBA, GL30C.GL_UNSIGNED_BYTE, 0);
            GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, 0);
        }

        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

        ci.cancel();
    }

    @Inject(
        method = "finishDownload",
        at = @At("HEAD"),
        cancellable = true
    )
    private void flashbackandroidfix$finishDownload(int width, int height, CallbackInfoReturnable<NativeImage> cir) {
        if (!this.isDownloading) {
            throw new IllegalStateException("Can't finish downloading before download has started");
        }
        this.isDownloading = false;

        NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, width, height, false);

        if (this.flashbackandroidfix$pboBroken) {
            MemoryUtil.memCopy(MemoryUtil.memAddress(this.flashbackandroidfix$syncBuffer), nativeImage.pixels, nativeImage.size);
            cir.setReturnValue(nativeImage);
            return;
        }

        GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, this.pboId);
        ByteBuffer buffer = GL30C.glMapBuffer(GL30C.GL_PIXEL_PACK_BUFFER, GL30C.GL_READ_ONLY);

        if (buffer != null) {
            // Copy bytes
            MemoryUtil.memCopy(MemoryUtil.memAddress(buffer), nativeImage.pixels, nativeImage.size);

            GL30C.glUnmapBuffer(GL30C.GL_PIXEL_PACK_BUFFER);
            GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, 0);

            cir.setReturnValue(nativeImage);
            return;
        }

        // First mapping failure: log the GL error once and degrade for good.
        int glError = GL11.glGetError();
        Flashback.LOGGER.warn("[flashback-androidfix] glMapBuffer returned null (glErr=0x{}), degrading export readback to synchronous glReadPixels",
                Integer.toHexString(glError));
        this.flashbackandroidfix$pboBroken = true;
        GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, 0);

        // Salvage this frame: rebind the FBO and read straight into the sync
        // buffer. The colour attachment may already hold the next frame, so
        // this path only ever runs on the single frame that failed.
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.flashbackandroidfix$fboId);
        GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 1);
        GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
        flashbackandroidfix$ensureSyncBuffer(width, height);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, this.flashbackandroidfix$syncBuffer);
        while (GL11.glGetError() != GL11.GL_NO_ERROR) {
            // Swallow so the failed read does not leak into the render thread's
            // own error checks.
        }
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

        MemoryUtil.memCopy(MemoryUtil.memAddress(this.flashbackandroidfix$syncBuffer), nativeImage.pixels, nativeImage.size);

        cir.setReturnValue(nativeImage);
    }

    @Inject(method = "close", at = @At("TAIL"))
    private void flashbackandroidfix$releaseSyncBuffer(CallbackInfo ci) {
        if (this.flashbackandroidfix$syncBuffer != null) {
            MemoryUtil.memFree(this.flashbackandroidfix$syncBuffer);
            this.flashbackandroidfix$syncBuffer = null;
        }
    }

    @Unique
    private void flashbackandroidfix$ensureSyncBuffer(int width, int height) {
        int needed = width * height * 4;
        if (this.flashbackandroidfix$syncBuffer == null || this.flashbackandroidfix$syncBuffer.capacity() < needed) {
            if (this.flashbackandroidfix$syncBuffer != null) {
                MemoryUtil.memFree(this.flashbackandroidfix$syncBuffer);
            }
            this.flashbackandroidfix$syncBuffer = MemoryUtil.memAlloc(needed);
        }
        this.flashbackandroidfix$syncBuffer.clear();
    }
}
