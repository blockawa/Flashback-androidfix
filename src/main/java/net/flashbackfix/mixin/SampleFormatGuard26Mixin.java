package net.flashbackfix.mixin;

import com.moulberry.flashback.exporting.AsyncFFmpegVideoWriter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 修 B（26.x / Flashback 0.43.3-0.43.6）：音频样本格式按编码器自动选择。
 *
 * <p>26.x 的 {@code AsyncFFmpegVideoWriter.tryStart} 同样无条件
 * {@code setSampleFormat(FLTP)}（0.43.3 字节码偏移 815、0.43.6 源码 160 行），显式
 * 格式会跳过 {@code FlashbackFFmpegFrameRecorder} 内建的 {@code sample_fmts()} 检查
 * 分支——fork 修复只在 else 分支生效，因此手动选 Opus 在 26.x 也会撞上
 * {@code avcodec_open2 -22}。
 *
 * <p>改传 {@code NONE(-1)} 让 fork 的检查分支接管：默认 fltp、编码器支持 S16 则改用
 * S16——libopus 得以开启，AAC/Vorbis 列表无 S16 保持 fltp 行为不变。0.43.3/0.43.6
 * 的方法名与调用点一致，一个 mixin 覆盖三个 26.x 版本。
 */
@Mixin(value = AsyncFFmpegVideoWriter.class, remap = false)
public abstract class SampleFormatGuard26Mixin {

    @Redirect(method = "tryStart(I)V", at = @At(
            value = "INVOKE",
            target = "Lcom/moulberry/flashback/exporting/FlashbackFFmpegFrameRecorder;setSampleFormat:(I)V"
    ))
    private static int flashbackandroidfix$autoSampleFormat(int sampleFormat) {
        return -1; // AV_SAMPLE_FMT_NONE：交给 recorder 按 sample_fmts() 自选
    }
}
