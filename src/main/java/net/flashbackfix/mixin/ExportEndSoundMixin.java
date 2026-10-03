package net.flashbackfix.mixin;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.exporting.ExportJob;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 修导出完成"叮"提示音丢失。
 *
 * <p>{@code ExportJob.run()} 的 finally 无条件 {@code stop()} 后立刻连播
 * NOTE_BLOCK_CHIME + NOTE_BLOCK_BELL，但真机 log 实证：安卓上导出结束瞬间音频
 * 设备焦点变化，SoundManager 会在同一秒整体重启（"Sound engine started" 与
 * ffmpeg 封口同秒出现），刚排队的两个 UI 音效随引擎重启被清空，叮永远听不到。
 *
 * <p>这里把 {@code run()} 里仅有的两处 {@code SoundManager.play} 重定向为延迟
 * 1.5 秒（避开引擎重启窗口）后经 {@code Minecraft.execute} 回主线程播放；前面的
 * {@code stop()} 保持原样。不重启的场景只是提示音晚 1.5 秒，无其他行为变化。
 *
 * <p>0.39.10（1.21.1/1.21.4/1.21.11）与 0.43.x（26.1/26.2/26.3）的该段代码逐行
 * 一致（仅行号差），共享层一份覆盖全 6 版本。SoundManager/SoundInstance 是 MC
 * 类型，类级保持默认 remap=true 以便 @At target 重映射到 intermediary。
 */
@Mixin(value = ExportJob.class)
public abstract class ExportEndSoundMixin {

    /**
     * 单线程守护调度器：只负责延时，实际播放经 Minecraft.execute 回主线程
     * （与 Flashback 自家 FlashbackAudioBuffer 的调度线程回主线程写法同款）。
     */
    private static final ScheduledExecutorService FLASHBACKANDROIDFIX_END_SOUND_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "flashbackandroidfix-end-sound");
                thread.setDaemon(true);
                return thread;
            });

    @Redirect(
            method = "run",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/resources/sounds/SoundManager;play(Lnet/minecraft/client/resources/sounds/SoundInstance;)V"
            ),
            require = 2
    )
    private void flashbackandroidfix$delayEndSound(SoundManager manager, SoundInstance instance) {
        FLASHBACKANDROIDFIX_END_SOUND_SCHEDULER.schedule(() -> {
            try {
                Minecraft.getInstance().execute(() -> manager.play(instance));
            } catch (Throwable t) {
                // 游戏可能已在提示音延迟期间退出，播放失败只记日志不打扰
                Flashback.LOGGER.warn("[flashback-androidfix] 导出完成音效播放失败: {}", t.toString());
            }
        }, 1500, TimeUnit.MILLISECONDS);
    }
}
