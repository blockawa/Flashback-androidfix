package net.flashbackfix;

import com.moulberry.flashback.Flashback;
import net.fabricmc.api.ClientModInitializer;

/**
 * Runs before anything touches {@code imgui.moulberry90.ImGui}, whose static initializer reads
 * {@code imgui.library.path} exactly once. FFmpeg does not need an entrypoint: it is only loaded
 * on the first export, long after mixins are applied.
 */
public final class AndroidNativesInit implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        try {
            AndroidNatives.installImgui();
        } catch (Throwable t) {
            Flashback.LOGGER.warn(
                    "[flashback-androidfix] imgui natives not installed, using Flashback's copy", t);
        }
    }
}
