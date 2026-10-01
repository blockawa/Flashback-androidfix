package net.flashbackfix.mixin;

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
 * itself). On a supported desktop platform it returns a hit and this mixin stays out of the way;
 * when it comes back empty we answer from this mod's {@code natives/} folder instead, so JavaCPP
 * carries on with its normal preload order, caching and dependency resolution.
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

        URL[] found = cir.getReturnValue();
        if (found != null && found.length > 0) return;

        URL[] ours = AndroidNatives.resolve(suffix);
        if (ours != null && ours.length > 0) {
            cir.setReturnValue(ours);
        }
    }
}
