package net.flashbackfix;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.combo_options.AudioCodec;
import com.moulberry.flashback.combo_options.VideoContainer;
import com.moulberry.flashback.configuration.FlashbackConfigV1;

/**
 * 导出音频编码兜底（两个版本的 mixin 共用）。
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
public final class ExportAudioGuard {

    private ExportAudioGuard() {}

    /** 把 {@code codec} 收敛到当前导出容器支持的音频编码集合内。 */
    public static AudioCodec fallback(AudioCodec codec) {
        if (codec == null) return null;
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
