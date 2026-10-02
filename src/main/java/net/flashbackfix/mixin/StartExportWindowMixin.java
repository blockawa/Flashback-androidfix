package net.flashbackfix.mixin;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.combo_options.AudioCodec;
import com.moulberry.flashback.combo_options.VideoCodec;
import com.moulberry.flashback.combo_options.VideoContainer;
import com.moulberry.flashback.configuration.FlashbackConfigV1;
import com.moulberry.flashback.editor.ui.windows.StartExportWindow;
import com.moulberry.flashback.exporting.ExportSettings;
import net.flashbackfix.ExportPathUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

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
 * <p>另包含修 A（导出音频编码兜底）的双版本注入点，见类底部
 * {@code flashbackandroidfix$fallbackAudioCodec039} / {@code flashbackandroidfix$fallbackAudioCodec26}；
 * 以及编码器下拉合并（只留编解码一个下拉），见
 * {@code flashbackandroidfix$collapseEncoderDropdown}。
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
     * 修 A：导出音频编码兜底。{@code createExportSettings} 的音频分支只判录音开关，从不
     * 校验 {@code config.internalExport.audioCodec} 是否被当前容器支持（视频有
     * {@code !contains → codecs[0]} 兜底）——默认 AAC + webm 会原样进 {@code ExportSettings}，
     * 最终在 {@code avformat_write_header} 处被拒（-22）崩溃。注入点是 lambda 内音频编码
     * 局部变量落栈处（该处 AudioCodec STORE 唯一），值在录音开关判断前收敛到支持列表内，
     * 逻辑与视频兜底同构；兜底实现见 {@code flashbackandroidfix$convergeAudioCodec}。
     *
     * <p>两个注入点按 Flashback 版本分叉且互斥（字节码核对：0.39.10 的 createExportSettings
     * 只有 {@code $2}、0.43.x 只有 {@code $0}），{@code require = 0} 让不存在的那半在本版本
     * 静默跳过，每版本只命中自己那个。
     */
    @ModifyVariable(method = "lambda$createExportSettings$2", at = @At("STORE"), ordinal = 0, require = 0)
    private static AudioCodec flashbackandroidfix$fallbackAudioCodec039(AudioCodec codec) {
        return flashbackandroidfix$convergeAudioCodec(codec);
    }

    /** 修 A 的 0.43.x（26.x）注入点，与上面互斥，说明见上。 */
    @ModifyVariable(method = "lambda$createExportSettings$0", at = @At("STORE"), ordinal = 0, require = 0)
    private static AudioCodec flashbackandroidfix$fallbackAudioCodec26(AudioCodec codec) {
        return flashbackandroidfix$convergeAudioCodec(codec);
    }

    /**
     * 合并编码器下拉：把 {@code renderVideoOptions} 内 UI 用的 {@code videoCodec.getEncoders()}
     * 调用（该方法内唯一一处，ordinal 0）重定向为只含探测首项（各格式的 mediacodec 硬编，
     * H264 即 h264_mediacodec）的单元素数组，使原逻辑 {@code encoders.length > 1} 永假、
     * Encoder（编码器）下拉不再渲染——导出窗口只留编解码一个下拉。
     *
     * <p>编码路径零影响：{@code createExportSettings} 取编码器与 {@code getSelectedEncoderForCodec}
     * 都在本方法之外调用 {@code getEncoders()}，拿到的仍是完整列表；{@code selectedVideoEncoder}
     * 保持原值不动——未手动选过时即默认（空 / 索引 0），编码自然落到首项硬编。
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
     * 编码器信息合并显示：编码器下拉已由 {@code flashbackandroidfix$collapseEncoderDropdown}
     * 移除，这里把编解码下拉的 label 追加为当前格式的探测首项硬编——H264 时显示
     * {@code 编解码 · h264_mediacodec}，切换格式后缀随之变化（H265 → hevc_mediacodec），
     * 使被合并的编码器名在唯一的下拉上仍然可见。
     *
     * <p>仅当被包参数为 {@link VideoCodec}（编解码下拉）时追加后缀；同一方法内其余
     * enumCombo 调用（容器 / 音频）不动。handler 内的 {@code getEncoders()} 调用
     * 位于合成方法而非 renderVideoOptions，不受 collapse redirect 影响，拿到完整列表。
     *
     * <p>target 为泛型方法擦除签名 {@code (String, Enum, Enum[]) -> Enum}，已对 6 版本
     * Flashback jar 逐一 javap 核对完全一致；26.x 另有的两参重载描述符不同，不会被拦。
     *
     * <p>用 ModifyArgs 而非 Redirect：enumCombo 声明为 {@code <T extends Enum<T>> T}，
     * Redirect handler 参数经擦除是裸 Enum，反向推断不出满足约束的 T，任何转发写法都会
     * 编译失败（CI 实证 no suitable method found）；ModifyArgs 只改实参、原调用指令
     * 原样保留，不写任何 enumCombo 调用，泛型问题根除。
     */
    @ModifyArgs(
        method = "renderVideoOptions",
        at = @At(
            value = "INVOKE",
            target = "Lcom/moulberry/flashback/editor/ui/ImGuiHelper;enumCombo(Ljava/lang/String;Ljava/lang/Enum;[Ljava/lang/Enum;)Ljava/lang/Enum;"
        )
    )
    private static void flashbackandroidfix$showEncoderInCodecLabel(Args args) {
        Enum<?> current = args.get(1);
        if (current instanceof VideoCodec videoCodec) {
            String[] encoders = videoCodec.getEncoders();
            if (encoders.length > 0) {
                args.set(0, args.<String>get(0) + " · " + encoders[0]);
            }
        }
    }

    /**
     * 导出音频编码兜底（原独立类 ExportAudioGuard 内联至此）。
     *
     * <p>Flashback 的 {@code createExportSettings} 只在关闭录音时把音频编码置空，从不校验它
     * 是否属于当前容器的支持列表（视频编码有同款 {@code contains} 兜底，音频没有）。默认值
     * AAC 遇上 webm 容器会原样穿透到 {@code avformat_write_header}，被 FFmpeg 以 -22 拒收
     * 并抛 RuntimeException 崩溃。
     *
     * <p>兜底规则：空值保持空值（= 无音频，不改变"录音关着就不出音频流"的语义）；非空但不在
     * 支持列表时换成列表首项；容器完全不支持音频时置空。校验本身失败也置空——宁可少一条
     * 音频流也不让导出崩掉。
     */
    private static AudioCodec flashbackandroidfix$convergeAudioCodec(AudioCodec codec) {
        if (codec == null) return codec;
        try {
            FlashbackConfigV1 config = Flashback.getConfig();
            if (config == null || config.internalExport == null) return codec;

            VideoContainer container = config.internalExport.container;
            if (container == null) return codec;

            AudioCodec[] supported = container.getSupportedAudioCodecs();
            if (supported == null || supported.length == 0) return null;

            for (AudioCodec option : supported) {
                if (option == codec) return codec;
            }
            Flashback.LOGGER.warn("[flashback-androidfix] audio codec {} not supported by {}, falling back to {}",
                    codec, container, supported[0]);
            return supported[0];
        } catch (Throwable t) {
            Flashback.LOGGER.warn("[flashback-androidfix] audio codec validation failed", t);
            return null;
        }
    }
}
