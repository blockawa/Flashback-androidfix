package net.flashbackfix.mixin;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.configuration.FlashbackConfigV1;
import com.moulberry.flashback.editor.ui.windows.StartExportWindow;
import com.moulberry.flashback.exporting.ExportSettings;
import net.flashbackfix.ExportPathUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Fixes export path handling on platforms where the native SDL file dialogs hang
 * (Android / PojavLauncher).
 *
 * <p>Instead of opening a system dialog, the path configured in the export settings is used
 * directly, following ReplayMod's rule for {@code advanced.renderPath}: relative paths resolve
 * against the game folder, absolute paths are accepted as-is.
 *
 * <p>A configured path is never rewritten: {@code getDefaultFilename} would otherwise reset
 * {@code defaultExportPath} to the game folder whenever {@code Files.exists} says the folder is
 * missing - which on Android happens for perfectly usable folders under
 * {@code /storage/emulated/0/...} (scoped storage turns the stat into EACCES). The configured
 * folder stays as it is; when it truly cannot be written the export fails instead of silently
 * relocating somewhere else.
 *
 * <p>The path field itself lives in the Preferences window ({@code PreferencesWindowMixin});
 * this mixin only keeps the folder seeding and replaces the native dialogs during export.
 */
@Mixin(value = StartExportWindow.class, remap = false)
public abstract class StartExportWindowMixin {

    @Unique private static Path flashbackandroidfix$lastFolder = null;

    /**
     * Seeds the configured path before Flashback's own handling runs: a blank path becomes the
     * default {@code flashback_videos} folder and the folder is force-created, so the check below
     * always starts from a path that is set. The default itself is unchanged by this mod.
     */
    @Inject(method = "getDefaultFilename", at = @At("HEAD"))
    private static void flashbackandroidfix$seedDefaultExportFolder(String name, String extension,
                                                                    FlashbackConfigV1 config,
                                                                    CallbackInfoReturnable<String> cir) {
        ExportPathUtil.configuredFolder(config);
    }

    /**
     * {@code getDefaultFilename} rewrites {@code defaultExportPath} to the game folder as soon as
     * {@code Files.exists} claims the configured folder is missing. On Android that check answers
     * EACCES for usable folders under {@code /storage/emulated/0/...} (scoped storage), so a path
     * the user configured - e.g. {@code /storage/emulated/0/Download} - would silently be replaced
     * by the game folder and stay that way forever.
     *
     * <p>The blank / null case never reaches this call (the {@code ||} chain short-circuits), so
     * reporting "exists" here only means: a configured path is honoured as-is. The folder has
     * already been force-created by the HEAD inject above; if writing it still fails, the export
     * fails loudly instead of relocating without asking.
     */
    @Redirect(
        method = "getDefaultFilename",
        at = @At(
            value = "INVOKE",
            target = "Ljava/nio/file/Files;exists(Ljava/nio/file/Path;[Ljava/nio/file/LinkOption;)Z"
        )
    )
    private static boolean flashbackandroidfix$keepConfiguredPath(Path path, LinkOption[] options) {
        return true;
    }

    @Redirect(
        method = "createExportSettings",
        at = @At(
            value = "INVOKE",
            target = "Lcom/moulberry/flashback/utils/AsyncFileDialogs;saveFileDialog(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)Ljava/util/concurrent/CompletableFuture;"
        )
    )
    private static CompletableFuture<String> flashbackandroidfix$saveFileDialog(String defaultPath, String defaultName,
                                                                                String filterDescription, String[] filters) {
        Path folder = ExportPathUtil.configuredFolder(Flashback.getConfig());
        if (folder == null) return CompletableFuture.completedFuture(null);

        flashbackandroidfix$lastFolder = folder;
        return CompletableFuture.completedFuture(folder.resolve(defaultName).toString());
    }

    @Redirect(
        method = "createExportSettings",
        at = @At(
            value = "INVOKE",
            target = "Lcom/moulberry/flashback/utils/AsyncFileDialogs;openFolderDialog(Ljava/lang/String;)Ljava/util/concurrent/CompletableFuture;"
        )
    )
    private static CompletableFuture<String> flashbackandroidfix$openFolderDialog(String defaultPath) {
        Path folder = ExportPathUtil.configuredFolder(Flashback.getConfig());
        if (folder == null) return CompletableFuture.completedFuture(null);

        flashbackandroidfix$lastFolder = folder;
        return CompletableFuture.completedFuture(folder.toString());
    }

    /**
     * {@code createExportSettings} stores {@code path.getParent()} as the default export path.
     * That is correct for regular files but off by one level for image sequences, where the
     * chosen folder itself is the output. Restore the folder that was actually selected.
     */
    @Inject(method = "createExportSettings", at = @At("RETURN"), cancellable = true)
    private static void flashbackandroidfix$restoreExportFolder(String name, FlashbackConfigV1 config,
                                                                CallbackInfoReturnable<CompletableFuture<ExportSettings>> cir) {
        Path folder = flashbackandroidfix$lastFolder;
        flashbackandroidfix$lastFolder = null;
        if (folder == null || cir.getReturnValue() == null) return;

        cir.setReturnValue(cir.getReturnValue().thenApply(settings -> {
            if (settings != null) {
                config.internalExport.defaultExportPath = folder.toString();
            }
            return settings;
        }));
    }

}
