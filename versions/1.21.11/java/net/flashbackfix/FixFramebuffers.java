package net.flashbackfix;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

public final class FixFramebuffers {

    private FixFramebuffers() {
    }

    public static int framebufferWidth(Window window) {
        int[] width = new int[1];
        int[] height = new int[1];
        GLFW.glfwGetFramebufferSize(window.handle(), width, height);
        return width[0] > 0 ? width[0] : 1;
    }

    public static int framebufferHeight(Window window) {
        int[] width = new int[1];
        int[] height = new int[1];
        GLFW.glfwGetFramebufferSize(window.handle(), width, height);
        return height[0] > 0 ? height[0] : 1;
    }

    public static RenderTarget resizeOrCreate(RenderTarget renderTarget, int width, int height) {
        return resizeOrCreate(renderTarget, width, height, false);
    }

    /**
     * @param useDepth 是否创建 depth attachment。作新栈 renderPass 合成目标的 RT
     *                 必须传 true：无 depth 的 RT 在 MobileGlues（GLES 底层）下会被
     *                 严格拒绝而 zink 容忍，导致合成静默失效（对齐 Flashback 的
     *                 partial present tempRT 创建参数）。
     */
    public static RenderTarget resizeOrCreate(RenderTarget renderTarget, int width, int height, boolean useDepth) {
        if (renderTarget == null) {
            renderTarget = new TextureTarget(null, width, height, useDepth);
        } else if (renderTarget.width != width || renderTarget.height != height) {
            renderTarget.resize(width, height);
        }
        return renderTarget;
    }

    public static void clear(RenderTarget renderTarget, int colour) {
        int oldReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int oldDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);

        GpuTexture colourTexture = renderTarget.getColorTexture();
        GpuTexture depthTexture = renderTarget.getDepthTexture();
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        if (colourTexture != null && !colourTexture.isClosed() && depthTexture != null && !depthTexture.isClosed()) {
            encoder.clearColorAndDepthTextures(colourTexture, colour, depthTexture, 1.0f);
        } else if (colourTexture != null && !colourTexture.isClosed()) {
            encoder.clearColorTexture(colourTexture, colour);
        } else if (depthTexture != null && !depthTexture.isClosed()) {
            encoder.clearDepthTexture(depthTexture, 1.0f);
        }

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldReadFbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, oldDrawFbo);
    }
}
