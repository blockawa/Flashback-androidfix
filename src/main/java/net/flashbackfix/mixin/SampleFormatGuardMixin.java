package net.flashbackfix.mixin;

import com.moulberry.flashback.exporting.AsyncFFmpegVideoWriter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 修 B（双版本）：音频样本格式按编码器自动选择。
 *
 * <p>{@code AsyncFFmpegVideoWriter} 无条件 {@code setSampleFormat(FLTP)}（0.39.10 构造器
 * 159 行、0.43.3 字节码偏移 815、0.43.6 源码 160 行），显式格式会跳过 recorder 内建的
 * {@code sample_fmts()} 检查分支——libopus 只支持 s16/flt，fltp 直灌导致
 * {@code avcodec_open2 -22}（手动选 Opus 必崩）。
 *
 * <p>改传 {@code NONE(-1)} 让检查分支接管：默认 fltp、编码器支持 S16 则改用 S16——
 * libopus 得以开启；AAC/Vorbis 的支持列表无 S16 会保持 fltp，行为不变。
 *
 * <p>两个注入点按 Flashback 版本分叉且互斥（字节码核对：0.39.10 构造器内是 javacv
 * recorder 且无 tryStart 方法；0.43.x 构造器内无 javacv 调用、修复点在 tryStart），
 * {@code require = 0} 让不存在的那半静默跳过，每版本只命中自己那个。
 */
@Mixin(value = AsyncFFmpegVideoWriter.class, remap = false)
public abstract class SampleFormatGuardMixin {

    /** 0.39.10（1.21.11）：构造器里的 javacv recorder。 */
    @Redirect(method = "<init>", at = @At(
            value = "INVOKE",
            target = "Lorg/bytedeco/javacv/FFmpegFrameRecorder;setSampleFormat(I)V"
    ), require = 0)
    private static int flashbackandroidfix$autoSampleFormat039(int sampleFormat) {
        return -1; // AV_SAMPLE_FMT_NONE：交给 recorder 按 sample_fmts() 自选
    }

    /** 0.43.x（26.x）：tryStart 里的 fork recorder。 */
    @Redirect(method = "tryStart(I)V", at = @At(
            value = "INVOKE",
            target = "Lcom/moulberry/flashback/exporting/FlashbackFFmpegFrameRecorder;setSampleFormat(I)V"
    ), require = 0)
    private static int flashbackandroidfix$autoSampleFormat26(int sampleFormat) {
        return -1; // AV_SAMPLE_FMT_NONE：交给 recorder 按 sample_fmts() 自选
    }
}
