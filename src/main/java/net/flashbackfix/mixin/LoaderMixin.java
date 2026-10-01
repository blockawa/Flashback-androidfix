package net.flashbackfix.mixin;

import com.moulberry.flashback.Flashback;
import net.flashbackfix.AndroidNatives;
import org.bytedeco.javacpp.ClassProperties;
import org.bytedeco.javacpp.Loader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.URL;

/**
 * Feeds the Android FFmpeg natives to JavaCPP.
 *
 * <p>Every lookup the FFmpeg load chain performs ends up in the 4-arg
 * {@code Loader.findLibrary(Class, ClassProperties, String, boolean)} - the 3-arg overload just
 * delegates to it, and {@code Loader.load(...)} calls it three times (preload, link, the library
 * itself).
 *
 * <p>When this mod bundles the requested library ({@code AndroidNatives.resolve} hits), that
 * answer now wins <em>unconditionally</em>, even if JavaCPP found something on its own. JavaCPP's
 * own hit can be a stale file from the other Minecraft version's install: both versions extract
 * into the same shared directory, and loading 26.x's FFmpeg 8 {@code libjniavcodec.so} under
 * 1.21.11's FFmpeg 6 bindings crashes the export window, because FFmpeg 8 no longer exports
 * {@code avcodec_close()}. Our extract step re-checks the file size against the bundled resource
 * on every hit, so always answering from it is what keeps the right natives in front of JavaCPP.
 * When we have no bundled copy (desktop platforms, unsupported ABIs), JavaCPP's own result stands
 * as before.
 */
@Mixin(value = Loader.class, remap = false)
public class LoaderMixin {

    @Inject(
        method = "findLibrary(Ljava/lang/Class;Lorg/bytedeco/javacpp/ClassProperties;Ljava/lang/String;Z)[Ljava/net/URL;",
        at = @At("RETURN"),
        cancellable = true
    )
    private static void flashbackandroidfix$findAndroidNatives(
            Class<?> cls, ClassProperties properties, String suffix, boolean force,
            CallbackInfoReturnable<URL[]> cir) {

        URL[] ours = AndroidNatives.resolve(suffix);
        if (ours == null || ours.length == 0) {
            // We don't bundle this one - leave JavaCPP's own answer alone.
            return;
        }

        URL[] found = cir.getReturnValue();
        if (found != null && found.length > 0) {
            Flashback.LOGGER.warn("[flashback-androidfix] findLibrary {} was answered by javacpp ({}); using bundled natives instead",
                    suffix, found[0]);
        }
        cir.setReturnValue(ours);
    }
}
