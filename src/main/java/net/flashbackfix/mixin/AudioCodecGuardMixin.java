package net.flashbackfix.mixin;

import com.moulberry.flashback.combo_options.AudioCodec;
import com.moulberry.flashback.editor.ui.windows.StartExportWindow;
import net.flashbackfix.ExportAudioGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 修 A（1.21.11 / Flashback 0.39.10）：导出设置的音频编码兜底。
 *
 * <p>{@code createExportSettings} 编译进 lambda 的音频分支只判断录音开关，从不校验
 * {@code config.internalExport.audioCodec} 是否被当前容器支持——视频编码则有
 * {@code !contains → codecs[0]} 的兜底。默认 AAC + webm 的组合因此会原样进入
 * {@code ExportSettings}，最终在 {@code avformat_write_header} 处被拒（-22）崩溃。
 *
 * <p>注入点：lambda 内音频编码局部变量落栈处（该方法里 {@code AudioCodec} 的 STORE
 * 唯一），值在后续录音开关判断之前被收敛到容器支持列表内，逻辑与视频兜底同构。
 * 兜底实现在 {@link ExportAudioGuard}，两个版本共用。
 */
@Mixin(value = StartExportWindow.class, remap = false)
public abstract class AudioCodecGuardMixin {

    @ModifyVariable(method = "lambda$createExportSettings$2", at = @At("STORE"), ordinal = 0)
    private static AudioCodec flashbackandroidfix$fallbackAudioCodec(AudioCodec codec) {
        return ExportAudioGuard.fallback(codec);
    }
}
