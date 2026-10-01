package net.flashbackfix.mixin;

import com.moulberry.flashback.combo_options.VideoCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.global.avcodec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fixes both Flashback encoder-probe failure modes seen on Android.
 *
 * <p>First, drops {@code *_mediacodec} encoders from Flashback's encoder list when this JVM has no
 * {@code android.media.MediaCodec} class.
 *
 * <p>FFmpeg's mediacodec probe can succeed through the NDK {@code AMediaCodec_*} path while
 * the real export later fails through the JNI path with {@code NoClassDefFoundError:
 * android/media/MediaCodec} (ZalithLauncher's JVM ships no Android framework), surfacing as
 * {@code avcodec_open2() error -542398533}. Rejecting the encoder at probe time makes
 * {@code getSelectedEncoderForCodec} fall back to the software {@code libopenh264}, which
 * this FFmpeg build bundles.
 *
 * <p>Second, guards the three {@code avcodec_close()} cleanup calls inside the probe. They link
 * against {@code libjniavcodec.so}, and when the runtime ends up with an FFmpeg 8 native instead
 * of the bundled FFmpeg 6.1.1 one (FFmpeg 8 removed the API, so that library exports no
 * {@code avcodec_close} symbol), the JNI lookup throws {@link UnsatisfiedLinkError} and opening
 * the export window crashes the game. The return value is discarded by the caller ({@code pop}),
 * so link failures can be swallowed and reported as success - the probe verdict itself already
 * came from {@code avcodec_open2()}.
 *
 * <p>{@code require = 0}: Flashback 0.43.x (26.x) never calls {@code avcodec_close} at all -
 * its FFmpeg 8 bindings don't declare it - so that injection point does not exist there and has
 * to be allowed to match zero times instead of hard-failing under the json's {@code defaultRequire: 1}.
 */
@Mixin(value = VideoCodec.class, remap = false)
public class VideoCodecMixin {

    @Inject(method = "doesEncoderWork", at = @At("HEAD"), cancellable = true)
    private static void flashbackandroidfix$rejectMediaCodec(AVCodec codec, CallbackInfoReturnable<Boolean> cir) {
        String name = codec.name().getString();
        if (name.endsWith("_mediacodec") && !androidMediaCodecClassPresent()) {
            cir.setReturnValue(false);
        }
    }

    @Redirect(
        method = "doesEncoderWork",
        at = @At(
            value = "INVOKE",
            target = "Lorg/bytedeco/ffmpeg/global/avcodec;avcodec_close(Lorg/bytedeco/ffmpeg/avcodec/AVCodecContext;)I"
        ),
        require = 0
    )
    private static int flashbackandroidfix$guardedAvcodecClose(AVCodecContext ctx) {
        try {
            return avcodec.avcodec_close(ctx);
        } catch (UnsatisfiedLinkError e) {
            return 0;
        }
    }

    private static boolean androidMediaCodecClassPresent() {
        try {
            Class.forName("android.media.MediaCodec");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
