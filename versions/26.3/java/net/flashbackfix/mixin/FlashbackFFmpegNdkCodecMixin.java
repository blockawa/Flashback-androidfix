package net.flashbackfix.mixin;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.exporting.FlashbackFFmpegFrameRecorder;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import static org.bytedeco.ffmpeg.global.avcodec.avcodec_open2;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_set;

/**
 * mediacodec 硬编强制走 NDK 路径（移植自 Flashback-Redroided 的同款逻辑，
 * 26.x 分叉：注入 {@code FlashbackFFmpegFrameRecorder}）。
 *
 * <p>0.43.x 的 {@code startUnsafe} 内视频（L589）/音频（L650）各有 {@code avcodec_open2}，
 * {@code video_codec}/{@code videoOptions} 结构与 Redroided 的 target 逐行同源
 * （26.1/26.2/26.3 源码核对一致，Redroided 0.43.6 生产验证）。
 *
 * <p>FFmpeg 的 mediacodec 编码器编了 JNI/NDK 两套实现，{@code ndk_codec} 默认 0 走
 * JNI——JNI 路径需要启动器 JVM 里存在 {@code android.media.MediaCodec} 这个 Java 类，
 * ZalithLauncher 等环境没有该类，{@code avcodec_open2} 直接 NoClassDefFoundError 崩
 * 导出（-542398533）。在 open 前往 AVDictionary 写 {@code ndk_codec=1} 可强制改走
 * NDK {@code AMediaCodec_*} 纯 native 路径（本 mod 的 bytedeco FFmpeg 8.1.2 已链接
 * libmediandk，二进制含该选项，均实测），完全不经过 Java 层。
 *
 * <p>手法与 Redroided 的 {@code @Inject}+{@code @Local} 等价但用原生 {@code @Redirect}：
 * 字典本来就是 avcodec_open2 的第三个参数，按 codec 名判断只对 {@code *_mediacodec}
 * 生效，音频那次原样放行（Redroided 每次都会盲塞，多余选项无害但不精确）。
 */
@Mixin(value = FlashbackFFmpegFrameRecorder.class, remap = false)
public abstract class FlashbackFFmpegNdkCodecMixin {

    @Redirect(
        method = "startUnsafe",
        at = @At(
            value = "INVOKE",
            target = "Lorg/bytedeco/ffmpeg/global/avcodec;avcodec_open2(Lorg/bytedeco/ffmpeg/avcodec/AVCodecContext;Lorg/bytedeco/ffmpeg/avcodec/AVCodec;Lorg/bytedeco/ffmpeg/avutil/AVDictionary;)I"
        )
    )
    private int flashbackandroidfix$forceNdkCodec(AVCodecContext ctx, AVCodec codec, AVDictionary options) {
        if (codec != null && codec.name() != null && codec.name().getString().endsWith("_mediacodec")) {
            av_dict_set(options, "ndk_codec", "1", 0);
            Flashback.LOGGER.warn("[flashback-androidfix] forcing ndk_codec=1 for {}", codec.name().getString());
        }
        return avcodec_open2(ctx, codec, options);
    }
}
