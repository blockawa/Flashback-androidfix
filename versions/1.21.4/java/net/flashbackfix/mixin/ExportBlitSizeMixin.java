package net.flashbackfix.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.exporting.ExportJob;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

/**
 * 导出启动瞬间画面缩到左下角+周围全黑的修复。
 *
 * <p>点开始导出后 {@code ExportJob.doExport} 置 {@code shouldChangeFramebufferSize=true}，
 * 此区间 {@code MixinWindow.getWidth()/getHeight()} 返回导出分辨率（假值）供游戏按导出
 * 尺寸渲染；但 present 的 blit 目标应是真实 backbuffer——某条未经 Flashback
 * flag=false 包围的 {@code blitToScreen} 路径直接把假值当屏幕尺寸，内容按
 * {@code viewport(0,0,导出W,导出H)} 画在 GL 原点（左下）一角，区域外即是黑底。
 *
 * <p>修法不逐帧猜路径，直接收口在 {@code RenderTarget.blitToScreen} 方法入口：
 * 导出区间（flag=true）把 width/height 参数替换为 glfw 查得的真实 backbuffer 尺寸，
 * 使一切经此方法的 present 都铺满全屏（与导出循环 L357 临时关 flag 后的行为一致）；
 * 非导出区间原样返回，零影响。
 *
 * <p>{@code (IIZ)V} 重载用 require=0：1.21.1 存在（Flashback MixinRenderTarget 注入
 * 它）、1.21.4 已移除该重载——不存在时静默跳过，两版均只命中自己真实存在的签名。
 * ModifyVariable 只改参数不动指令，与 Flashback/本 mod 在 runTick 同一调用点的
 * @Inject 注入互不干扰（@Redirect 删指令会破坏它们的定位，故不采用）。
 *
 * <p>真实尺寸用 glfwGetCurrentContext+glfwGetFramebufferSize 现查：绕开一切被
 * MixinWindow 重写的取值路径，blit 所在渲染线程必持有当前 context。
 */
@Mixin(RenderTarget.class)
public abstract class ExportBlitSizeMixin {

    @Unique
    private static boolean flashbackandroidfix$exporting() {
        ExportJob job = Flashback.EXPORT_JOB;
        return job != null && job.shouldChangeFramebufferSize();
    }

    @Unique
    private static int flashbackandroidfix$framebufferSize(boolean width) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            GLFW.glfwGetFramebufferSize(GLFW.glfwGetCurrentContext(), w, h);
            return width ? w.get(0) : h.get(0);
        }
    }

    @ModifyVariable(method = "blitToScreen(II)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int flashbackandroidfix$realWidthII(int width) {
        return flashbackandroidfix$exporting() ? flashbackandroidfix$framebufferSize(true) : width;
    }

    @ModifyVariable(method = "blitToScreen(II)V", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private int flashbackandroidfix$realHeightII(int height) {
        return flashbackandroidfix$exporting() ? flashbackandroidfix$framebufferSize(false) : height;
    }

    @ModifyVariable(method = "blitToScreen(IIZ)V", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private int flashbackandroidfix$realWidthIIZ(int width) {
        return flashbackandroidfix$exporting() ? flashbackandroidfix$framebufferSize(true) : width;
    }

    @ModifyVariable(method = "blitToScreen(IIZ)V", at = @At("HEAD"), argsOnly = true, ordinal = 1, require = 0)
    private int flashbackandroidfix$realHeightIIZ(int height) {
        return flashbackandroidfix$exporting() ? flashbackandroidfix$framebufferSize(false) : height;
    }
}
