package net.flashbackfix.mixin;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.combo_options.VideoCodec;
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
 *
 * <p>另包含编码器 UI 合并与编码器强制（见类底部 {@code flashbackandroidfix$collapseEncoderDropdown}
 * 与 {@code flashbackandroidfix$forceHardwareEncoder}）：删除导出设置里的 Encoder 第二下拉，
 * 并把实际编码器锁到探测首项硬编（正常设备即 mediacodec），六版本统一走这两个注入
 * （1.21.1 发行 jar 与其余五版同构，经 javap 实证）。
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

    /**
     * 编码器下拉合并：导出设置原本有两个下拉（编解码 + 编码器），这里让
     * {@code renderVideoOptions} 内的数据源调用 {@code getEncoders()}（ordinal = 0）
     * 只返回探测首项，配合原生 {@code encoders.length > 1} 渲染条件使 Encoder 第二下拉
     * 整体不再出现，只留编解码一个下拉。
     *
     * <p>仅影响 UI 渲染：handler 自身与编码路径的 {@code getEncoders()} 调用都在本方法
     * 之外，拿到的仍是完整列表。实际编码器由 {@code flashbackandroidfix$forceHardwareEncoder}
     * 强制锁到探测首项硬编（六版本统一，含 1.21.1）。
     *
     * <p>六版本（1.21.1 / 1.21.4 / 1.21.11 / 26.1.2 / 26.2 / 26.3）方法边界已逐一核对：
     * 下拉调用均在 {@code renderVideoOptions} 内且各仅此一处，签名一致。
     */
    @Redirect(
        method = "renderVideoOptions",
        at = @At(
            value = "INVOKE",
            target = "Lcom/moulberry/flashback/combo_options/VideoCodec;getEncoders()[Ljava/lang/String;",
            ordinal = 0
        )
    )
    private static String[] flashbackandroidfix$collapseEncoderDropdown(VideoCodec videoCodec) {
        String[] encoders = videoCodec.getEncoders();
        if (encoders.length <= 1) return encoders;
        return new String[]{encoders[0]};
    }

    /**
     * 强制视频编码器为探测首项硬编：{@code getSelectedEncoderForCodec} 是 0.39.10-for-1.21.4+
     * 与 0.43.x 五版本唯一的编码器取值入口（原生逻辑：配置名在有效列表内则用之，否则首项
     * 兜底），在 RETURN 直接改为恒返回首项——{@code getEncoders()} 按
     * hardware → hybrid → software → avoid 分桶，首项即 mediacodec 硬编
     * （H264 → h264_mediacodec），配置里手选残留的编码器名被无条件覆盖。
     *
     * <p>{@code require = 0} 兜底：六版发行 jar 均含此方法（1.21.1 经 javap 实证同样为
     * String 版 getSelectedEncoderForCodec——工作区参考源码与发行 jar 不符，一律以 jar 为准），
     * 正常六版全命中；保留 {@code require = 0} 是防未来版本移除该方法时静默降级而非崩溃。
     *
     * <p>handler 内 {@code useVideoCodec.getEncoders()} 位于 mixin 合成方法、不在
     * {@code renderVideoOptions} 内，不受 collapse redirect 影响，拿到完整列表。
     */
    @Inject(method = "getSelectedEncoderForCodec", at = @At("RETURN"), cancellable = true, require = 0)
    private static void flashbackandroidfix$forceHardwareEncoder(FlashbackConfigV1 config, VideoCodec useVideoCodec,
                                                                 CallbackInfoReturnable<String> cir) {
        String[] encoders = useVideoCodec.getEncoders();
        if (encoders == null || encoders.length == 0) return;
        cir.setReturnValue(encoders[0]);
    }

}
