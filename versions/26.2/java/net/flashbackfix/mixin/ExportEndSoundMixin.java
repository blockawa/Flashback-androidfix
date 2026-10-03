package net.flashbackfix.mixin;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.exporting.ExportJob;
import com.moulberry.flashback.playback.ReplayServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 修导出完成"叮"提示音丢失（PlayResult 新签名版，1.21.11/26.x）。
 *
 * <p>{@code ExportJob.run()} 的 finally 无条件 {@code stop()} 后立刻连播
 * NOTE_BLOCK_CHIME + NOTE_BLOCK_BELL，真机实测安卓上两处断点叠加导致叮声必丢：
 * ①导出结束瞬间 SoundManager 同秒整体重启（"Sound engine started" 与 ffmpeg
 * 封口同秒出现），立即播放的音效随引擎重启被清空；②仅延迟播放又撞上 Flashback
 * 自家 {@code MixinSoundEngine.play} 的屏蔽——{@code EXPORT_JOB == null &&
 * replayPaused} 时返回 NOT_STARTED 拒绝一切音效（防冻结回放出声），而 finally
 * 已把 replayPaused 置 true、延迟 1.5 秒后 EXPORT_JOB 必已清空，叮声正落进屏蔽
 * 窗口被吞。
 *
 * <p>这里把 {@code run()} 里仅有的两处 {@code SoundManager.play} 重定向为延迟
 * 1.5 秒（避开引擎重启窗口）后经 {@code Minecraft.execute} 回主线程、再经
 * {@code flashbackandroidfix$playEndSound} 播放——后者仅在屏蔽条件成立时临时
 * 放行 replayPaused 绕开屏蔽，同步播完立即恢复冻结。前面的 {@code stop()} 保持
 * 原样；不重启的场景只是提示音晚 1.5 秒，无其他行为变化。
 *
 * <p><b>按版本分文件的原因</b>：MC 1.21.9 起 {@code SoundManager.play} 返回值由
 * {@code void} 改为 {@code SoundEngine$PlayResult}——本版 call site 是 PlayResult
 * （Flashback 自家 MixinMultiPlayerGameMode 的 @At target 同款描述符），1.21.1/1.21.4
 * 则是 {@code )V}（发行 jar 字节码 javap 实证），用返回 void 的同名文件。@At 描述符
 * 与 handler 返回类型必须与 call site 精确一致，无法共存于一份文件。
 * {@code run()} 内 play 恰好 2 处（require = 2），两处返回值均被丢弃，handler
 * 返回 null 安全。
 *
 * <p>类级保持默认 remap=true：SoundManager/SoundInstance/SoundEngine 是 MC 类型
 * 需重映射，Flashback 的 method "run" 在映射表无条目时原样保留（未映射成员原样保留是 mixin AP 的既定行为）。
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
                    target = "Lnet/minecraft/client/sounds/SoundManager;play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;"
            ),
            require = 2
    )
    private SoundEngine.PlayResult flashbackandroidfix$delayEndSound(SoundManager manager, SoundInstance instance) {
        FLASHBACKANDROIDFIX_END_SOUND_SCHEDULER.schedule(() -> {
            try {
                Minecraft.getInstance().execute(() -> flashbackandroidfix$playEndSound(manager, instance));
            } catch (Throwable t) {
                // 游戏可能已在提示音延迟期间退出，播放失败只记日志不打扰
                Flashback.LOGGER.warn("[flashback-androidfix] 导出完成音效播放失败: {}", t.toString());
            }
        }, 1500, TimeUnit.MILLISECONDS);
        return null;
    }

    /**
     * 回主线程后的实际播放：仅当 Flashback 的音效屏蔽（{@code EXPORT_JOB == null}
     * 且 {@code replayPaused}，防冻结回放出声）恰好成立时，临时把 replayPaused 置
     * false 放行，同步播放完立即恢复 true。读写全在主线程同一次调用栈内完成，
     * 冻结状态不会被其他逻辑观察到中间值；若导出收尾超过 1.5 秒（EXPORT_JOB 尚未
     * 清空）屏蔽本就不成立，直接播放。
     */
    private void flashbackandroidfix$playEndSound(SoundManager manager, SoundInstance instance) {
        ReplayServer replayServer = Flashback.getReplayServer();
        boolean resumeFreeze = false;
        if (replayServer != null && replayServer.replayPaused && Flashback.EXPORT_JOB == null) {
            replayServer.replayPaused = false;
            resumeFreeze = true;
        }
        try {
            manager.play(instance);
        } finally {
            if (resumeFreeze) {
                replayServer.replayPaused = true;
            }
        }
    }
}
