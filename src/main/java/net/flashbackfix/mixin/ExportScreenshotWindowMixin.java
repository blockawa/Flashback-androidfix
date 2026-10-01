package net.flashbackfix.mixin;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.editor.ui.windows.ExportScreenshotWindow;
import net.flashbackfix.ExportPathUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Screenshot export opens the same native save dialog as video export, which hangs on
 * Android. Write straight to the folder configured in the export settings instead.
 */
@Mixin(value = ExportScreenshotWindow.class, remap = false)
public abstract class ExportScreenshotWindowMixin {

    @Redirect(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lcom/moulberry/flashback/utils/AsyncFileDialogs;saveFileDialog(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)Ljava/util/concurrent/CompletableFuture;"
        )
    )
    private static CompletableFuture<String> flashbackandroidfix$saveFileDialog(String defaultPath, String defaultName,
                                                                                String filterDescription, String[] filters) {
        Path folder = ExportPathUtil.configuredFolder(Flashback.getConfig());
        if (folder == null) return CompletableFuture.completedFuture(null);

        return CompletableFuture.completedFuture(folder.resolve(defaultName).toString());
    }
}
