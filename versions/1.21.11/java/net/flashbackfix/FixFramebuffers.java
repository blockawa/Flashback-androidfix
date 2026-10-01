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
        if (renderTarget == null) {
            renderTarget = new TextureTarget(null, width, height, false);
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
