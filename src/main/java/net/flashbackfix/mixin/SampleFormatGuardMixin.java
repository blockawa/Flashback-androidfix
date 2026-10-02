package net.flashbackfix.mixin;

import com.moulberry.flashback.exporting.AsyncFFmpegVideoWriter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 修 B（1.21.11 / Flashback 0.39.10）：音频样本格式按编码器自动选择。
 *
 * <p>{@code AsyncFFmpegVideoWriter} 构造器里无条件 {@code setSampleFormat(FLTP)}，而
 * recorder（javacv {@code FFmpegFrameRecorder}）的规则是"显式格式=不干预"——直接跳过
 * 内建的 {@code sample_fmts()} 检查分支。libopus 只支持 s16/flt，fltp 直灌导致
 * {@code avcodec_open2 -22}（手动选 Opus 必崩）。
 *
 * <p>改传 {@code NONE(-1)} 即可让 recorder 走它自己的检查分支：默认 fltp、若编码器
 * 支持 S16 则改用 S16——libopus 得到 s16 得以开启，AAC/Vorbis 的支持列表里没有 S16
 * 会保持 fltp，行为不变。这正是上游 0.43.x fork recorder 时依赖的同一分支。
 */
@Mixin(value = AsyncFFmpegVideoWriter.class, remap = false)
public abstract class SampleFormatGuardMixin {

    @Redirect(method = "<init>", at = @At(
            value = "INVOKE",
            target = "Lorg/bytedeco/javacv/FFmpegFrameRecorder;setSampleFormat:(I)V"
    ))
    private static int flashbackandroidfix$autoSampleFormat(int sampleFormat) {
        return -1; // AV_SAMPLE_FMT_NONE：交给 recorder 按 sample_fmts() 自选
    }
}
