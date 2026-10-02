package net.flashbackfix.mixin;

import com.moulberry.flashback.combo_options.AudioCodec;
import com.moulberry.flashback.editor.ui.windows.StartExportWindow;
import net.flashbackfix.ExportAudioGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 修 A（26.x / Flashback 0.43.3-0.43.6）：导出设置的音频编码兜底。
 *
 * <p>与 0.39.10 同一个漏洞：视频编码有 {@code !contains → codecs[0]} 兜底，音频只判
 * 录音开关与支持列表长度，当前编码不在列表时原样放行。26.x 的正常 UI 路径被 FFmpeg 8
 * 的下拉过滤（enumCombo 修正）挡住了，但手改配置等绕过路径仍会把非法组合送进
 * {@code write_header}——这里补上与视频同构的最后一道兜底。
 *
 * <p>0.43.3 与 0.43.6 的 lambda 方法名一致（{@code lambda$createExportSettings$0}，
 * 已按两版本字节码核对），一个 mixin 覆盖 26.1.2 / 26.2 / 26.3。兜底实现在
 * {@link ExportAudioGuard}，两个版本共用。
 */
@Mixin(value = StartExportWindow.class, remap = false)
public abstract class AudioCodecGuard26Mixin {

    @ModifyVariable(method = "lambda$createExportSettings$0", ordinal = 0)
    private static AudioCodec flashbackandroidfix$fallbackAudioCodec(AudioCodec codec) {
        return ExportAudioGuard.fallback(codec);
    }
}
