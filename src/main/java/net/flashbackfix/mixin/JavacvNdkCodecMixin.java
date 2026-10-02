package net.flashbackfix.mixin;

import com.moulberry.flashback.Flashback;
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
 * 1.21.x 分叉：注入 javacv 的 {@code FFmpegFrameRecorder}）。
 *
 * <p>1.21.1/1.21.4/1.21.11 的导出用 javacv {@code FFmpegFrameRecorder}（0.39.x 尚无
 * {@code FlashbackFFmpegFrameRecorder}），其 {@code startUnsafe} 内视频/音频各有
 * 一次 {@code avcodec_open2}，签名与 0.43.x 逐字一致（javacv 1.5.10 字节码实证）。
 *
 * <p>FFmpeg 的 mediacodec 编码器编了 JNI/NDK 两套实现，{@code ndk_codec} 默认 0 走
 * JNI——JNI 路径需要启动器 JVM 里存在 {@code android.media.MediaCodec} 这个 Java 类，
 * ZalithLauncher 等环境没有该类，{@code avcodec_open2} 直接 NoClassDefFoundError 崩
 * 导出（-542398533）。在 open 前往 AVDictionary 写 {@code ndk_codec=1} 可强制改走
 * NDK {@code AMediaCodec_*} 纯 native 路径（本 mod 的 bytedeco FFmpeg 6.1.1 已链接
 * libmediandk，二进制含该选项，均实测），完全不经过 Java 层。
 *
 * <p>target 用字符串：javacv 非 Flashback 类，避免编译期依赖它是否随 Flashback jar
 * 提供；运行时 1.21.x 与 26.x 的 Flashback 均 shadow 打包了 javacv。按 codec 名判断
 * 只对 {@code *_mediacodec} 生效，音频那次（及 PNG 序列等其它实例）原样放行。
 */
@Mixin(targets = "org.bytedeco.javacv.FFmpegFrameRecorder", remap = false)
public abstract class JavacvNdkCodecMixin {

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
