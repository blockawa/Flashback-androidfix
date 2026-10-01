package net.flashbackfix.mixin;

import com.moulberry.flashback.combo_options.VideoCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Drops {@code *_mediacodec} encoders from Flashback's encoder list when this JVM has no
 * {@code android.media.MediaCodec} class.
 *
 * <p>FFmpeg's mediacodec probe can succeed through the NDK {@code AMediaCodec_*} path while
 * the real export later fails through the JNI path with {@code NoClassDefFoundError:
 * android/media/MediaCodec} (ZalithLauncher's JVM ships no Android framework), surfacing as
 * {@code avcodec_open2() error -542398533}. Rejecting the encoder at probe time makes
 * {@code getSelectedEncoderForCodec} fall back to the software {@code libopenh264}, which
 * this FFmpeg build bundles.
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

    private static boolean androidMediaCodecClassPresent() {
        try {
            Class.forName("android.media.MediaCodec");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
