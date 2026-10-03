package net.flashbackfix.mixin;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.exporting.ExportJob;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 修导出完成"叮"提示音丢失（void 旧签名版，仅 1.21.1/1.21.4）。
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
 * <p><b>按版本分文件的原因</b>：MC 1.21.9 起 {@code SoundManager.play} 返回值由
 * {@code void} 改为 {@code SoundEngine$PlayResult}——1.21.1/1.21.4 的 call site
 * 是 {@code )V}（发行 jar 字节码 javap 实证），1.21.11/26.x 是 PlayResult（用同款
 * 返回版 {@code ExportEndSoundMixin}）。@At 描述符与 handler 返回类型必须与 call
 * site 精确一致，无法共存于一份文件。{@code run()} 内 play 恰好 2 处（require = 2）。
 *
 * <p>类级保持默认 remap=true：SoundManager/SoundInstance 是 MC 类型需重映射，
 * Flashback 的 method "run" 在映射表无条目时原样保留（未映射成员原样保留是 mixin AP 的既定行为）。
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
                    target = "Lnet/minecraft/client/sounds/SoundManager;play(Lnet/minecraft/client/resources/sounds/SoundInstance;)V"
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
