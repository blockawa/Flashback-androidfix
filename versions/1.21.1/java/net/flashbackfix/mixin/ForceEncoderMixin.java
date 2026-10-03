package net.flashbackfix.mixin;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.combo_options.VideoCodec;
import com.moulberry.flashback.configuration.FlashbackConfigV1;
import com.moulberry.flashback.editor.ui.windows.StartExportWindow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 1.21.1（0.39.10-for-MC1.21.1）专属：强制视频编码器为探测首项硬编。
 *
 * <p>该版没有 {@code getSelectedEncoderForCodec}，编码器在 {@code createExportSettings}
 * 内以 {@code getEncoders()[selectedVideoEncoder[0]]} 索引直取；且该版 config 的
 * {@code selectedVideoEncoder} 是 {@code int[]}（1.21.4+ 为 String），类型分叉使这部分
 * 代码无法放进共享文件，故放在本版本专属源集（其余五版的强制走共享
 * {@code StartExportWindowMixin.forceHardwareEncoder}，require = 0 互斥命中）。
 *
 * <p>redirect 拦下 {@code getEncoders()} 后归零残留的选择索引，随后的索引取值恒落到
 * 首项——按 hardware → hybrid → software → avoid 分桶即 mediacodec 硬编
 * （H264 → h264_mediacodec），旧版本 UI 里手选过的索引残留被无条件覆盖。
 * {@code createExportSettings} 内该调用仅此一处（ordinal = 0，字节码核对），
 * 方法外与 UI 的 {@code getEncoders()} 调用不受影响。
 */
@Mixin(value = StartExportWindow.class, remap = false)
public abstract class ForceEncoderMixin {

    @Redirect(
        method = "createExportSettings",
        at = @At(
            value = "INVOKE",
            target = "Lcom/moulberry/flashback/combo_options/VideoCodec;getEncoders()[Ljava/lang/String;",
            ordinal = 0
        )
    )
    private static String[] flashbackandroidfix$forceHardwareEncoder(VideoCodec videoCodec) {
        String[] encoders = videoCodec.getEncoders();
        FlashbackConfigV1 config = Flashback.getConfig();
        if (config != null && config.internalExport != null
                && config.internalExport.selectedVideoEncoder != null
                && config.internalExport.selectedVideoEncoder.length > 0) {
            config.internalExport.selectedVideoEncoder[0] = 0; // 归零残留索引，取值恒落首项
        }
        return encoders;
    }
}
