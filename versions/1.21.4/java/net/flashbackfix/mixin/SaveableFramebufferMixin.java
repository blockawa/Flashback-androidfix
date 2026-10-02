package net.flashbackfix.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.exporting.SaveableFramebuffer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
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
 * 替换 {@link SaveableFramebuffer} 的 PBO 像素下载（1.21.1 / 1.21.4 旧渲染栈版）。
 *
 * <p>原实现经像素包缓冲读取帧数据，{@code glMapBuffer} 返回空即抛
 * {@code IllegalStateException: OpenGL error occurred while mapping buffer}——这正是
 * Android GL 翻译层（ANGLE/MobileGlues）上的必然现象：像素包缓冲无法映射，导出的
 * 第一帧就把游戏打死（堆栈指向 SaveableFramebuffer.finishDownload:67，与 1.21.11
 * 修过的崩溃逐字相同）。
 *
 * <p>两个下载方法都在 HEAD 整体替换：映射失败时记一次 GL 错误日志，此后该
 * framebuffer 永久降级为同步 {@code glReadPixels} 读进常驻离堆缓冲，导出继续。
 * 降级读发生在 start 阶段（与 PBO 路径抓取画面的同一时刻），后续帧画面正确；
 * 映射失败的那一帧在 finish 时补读一次，色彩附件可能已显示下一帧——仅此一帧
 * 有瑕疵，不再崩溃。
 *
 * <p>与 1.21.11 版的差异：旧渲染栈没有 GlTexture/GpuTexture，直接使用 Flashback
 * 自己的 {@code RenderTarget.bindWrite/unbindWrite} 绑定帧缓冲（原版字节码即调用
 * 这两个方法，此处逐字沿用），省去了新栈取 FBO 的绕行。
 */
@Mixin(value = SaveableFramebuffer.class, remap = false)
public class SaveableFramebufferMixin {

    @Shadow
    private int pboId;

    @Shadow
    private boolean isDownloading;

    /** startDownload 时记住的帧缓冲，供 finishDownload 的抢救路径重新绑定。 */
    @Unique
    private @Nullable RenderTarget flashbackandroidfix$framebuffer;

    @Unique
    private boolean flashbackandroidfix$pboBroken = false;

    @Unique
    private @Nullable ByteBuffer flashbackandroidfix$syncBuffer;

    @Inject(
        method = "startDownload",
        at = @At("HEAD"),
        cancellable = true
    )
    private void flashbackandroidfix$startDownload(RenderTarget framebuffer, int width, int height, CallbackInfo ci) {
        if (this.isDownloading) {
            throw new IllegalStateException("Can't start downloading while already downloading");
        }
        this.isDownloading = true;
        this.flashbackandroidfix$framebuffer = framebuffer;

        GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 1);
        GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);

        if (this.flashbackandroidfix$pboBroken) {
            framebuffer.bindWrite(true);
            flashbackandroidfix$ensureSyncBuffer(width, height);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, this.flashbackandroidfix$syncBuffer);
            framebuffer.unbindWrite();
        } else {
            if (this.pboId == -1) {
                this.pboId = GL30C.glGenBuffers();

                GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, this.pboId);
                GL30C.glBufferData(GL30C.GL_PIXEL_PACK_BUFFER, (long) width * height * 4, GL30C.GL_STREAM_READ);
                GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, 0);
            }

            framebuffer.bindWrite(true);
            GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, this.pboId);
            GL30C.glReadPixels(0, 0, width, height, GL30C.GL_RGBA, GL30C.GL_UNSIGNED_BYTE, 0);
            GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, 0);
            framebuffer.unbindWrite();
        }

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
            // 拷贝像素
            MemoryUtil.memCopy(MemoryUtil.memAddress(buffer), nativeImage.pixels, nativeImage.size);

            GL30C.glUnmapBuffer(GL30C.GL_PIXEL_PACK_BUFFER);
            GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, 0);

            cir.setReturnValue(nativeImage);
            return;
        }

        // 首次映射失败：记一次 GL 错误日志并永久降级。
        int glError = GL11.glGetError();
        Flashback.LOGGER.warn("[flashback-androidfix] glMapBuffer returned null (glErr=0x{}), degrading export readback to synchronous glReadPixels",
                Integer.toHexString(glError));
        this.flashbackandroidfix$pboBroken = true;
        GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, 0);

        // 抢救本帧：重绑帧缓冲直接读进同步缓冲。色彩附件可能已是下一帧，
        // 所以这条补读路径只会在映射失败的那唯一一帧上执行。
        RenderTarget framebuffer = this.flashbackandroidfix$framebuffer;
        if (framebuffer != null) {
            framebuffer.bindWrite(true);
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 1);
            GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
            flashbackandroidfix$ensureSyncBuffer(width, height);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, this.flashbackandroidfix$syncBuffer);
            while (GL11.glGetError() != GL11.GL_NO_ERROR) {
                // 吞掉错误，避免这次补读泄漏进渲染线程自身的错误检查。
            }
            framebuffer.unbindWrite();
        }

        if (this.flashbackandroidfix$syncBuffer != null) {
            MemoryUtil.memCopy(MemoryUtil.memAddress(this.flashbackandroidfix$syncBuffer), nativeImage.pixels, nativeImage.size);
        }

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
