package net.flashbackfix;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.RenderSystem.AutoStorageIndexBuffer;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.OptionalInt;
import net.minecraft.client.renderer.CachedOrthoProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Full-screen blits used by the composite present: the game frame is cropped in
 * with an opaque pass, then the imgui overlay is blended on top of it with
 * {@link #blitOverlay}. Flashback's own {@code FramebufferUtils.blitTo} is not
 * callable from here because that jar ships with intermediary mappings while
 * this mod compiles against Mojmap (see {@link FixFramebuffers}).
 */
public final class CompositeBlit {

    private static final CachedOrthoProjectionMatrixBuffer projectionBuffers =
            new CachedOrthoProjectionMatrixBuffer("flashbackandroidfix overlay blit", 1000.0f, 3000.0f, true);

    private static RenderPipeline cropBlitPipeline;
    private static RenderPipeline overlayBlitPipeline;

    private CompositeBlit() {
    }

    private static RenderPipeline.Builder baseBuilder(String id) {
        return RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("flashbackandroidfix", "pipeline/" + id))
                .withVertexShader(Identifier.fromNamespaceAndPath("flashback", "core/blit_screen_old"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("flashback", "core/blit_screen_old"))
                .withSampler("InSampler")
                .withDepthWrite(false)
                .withCull(false)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withVertexFormat(DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS);
    }

    private static RenderPipeline cropPipeline() {
        if (cropBlitPipeline == null) {
            cropBlitPipeline = RenderPipelines.register(baseBuilder("crop_blit").build());
        }
        return cropBlitPipeline;
    }

    private static RenderPipeline overlayPipeline() {
        if (overlayBlitPipeline == null) {
            overlayBlitPipeline = RenderPipelines.register(
                    baseBuilder("overlay_blit").withBlend(BlendFunction.TRANSLUCENT).build());
        }
        return overlayBlitPipeline;
    }

    /**
     * Crops the game frame out of {@code from} and writes it over {@code to}.
     * Local copy of Flashback's {@code FramebufferUtils.blitTo}: that jar is
     * published with intermediary mappings, so its Minecraft-typed signature
     * does not line up with this mod's Mojmap compile classpath.
     */
    public static void blitCrop(GpuTextureView from, RenderTarget to, int width, int height,
            float x1, float y1, float x2, float y2) {
        blit(cropPipeline(), from, to, width, height, x1, y1, x2, y2);
    }

    public static void blitOverlay(GpuTextureView from, RenderTarget to, int width, int height) {
        blit(overlayPipeline(), from, to, width, height, 0.0f, 0.0f, 1.0f, 1.0f);
    }

    private static void blit(RenderPipeline pipeline, GpuTextureView from, RenderTarget to,
            int width, int height, float x1, float y1, float x2, float y2) {
        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix();
        modelViewStack.set(new Matrix4f().translation(0.0f, 0.0f, -2000.0f));
        GpuBufferSlice oldProjectionMatrix = RenderSystem.getProjectionMatrixBuffer();
        ProjectionType oldProjectionType = RenderSystem.getProjectionType();
        RenderSystem.setProjectionMatrix(projectionBuffers.getBuffer(width, height), ProjectionType.ORTHOGRAPHIC);
        try {
            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            builder.addVertex(width * x1, height * y2, 0.0f).setUv(0.0f, 0.0f);
            builder.addVertex(width * x2, height * y2, 0.0f).setUv(1.0f, 0.0f);
            builder.addVertex(width * x2, height * y1, 0.0f).setUv(1.0f, 1.0f);
            builder.addVertex(width * x1, height * y1, 0.0f).setUv(0.0f, 1.0f);
            try (MeshData meshData = builder.buildOrThrow()) {
                AutoStorageIndexBuffer autoStorageIndexBuffer = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
                GpuBuffer gpuBuffer = autoStorageIndexBuffer.getBuffer(6);
                GpuBuffer vertexBuffer = DefaultVertexFormat.POSITION_TEX.uploadImmediateVertexBuffer(meshData.vertexBuffer());
                GpuBufferSlice gpuBufferSlice = RenderSystem.getDynamicUniforms()
                        .writeTransform(RenderSystem.getModelViewMatrix(), new Vector4f(1.0F, 1.0F, 1.0F, 1.0F),
                                new Vector3f(), new Matrix4f());
                try (RenderPass renderPass = RenderSystem.getDevice()
                        .createCommandEncoder()
                        .createRenderPass(() -> "flashbackandroidfix overlay blit", to.getColorTextureView(), OptionalInt.empty())) {
                    renderPass.setPipeline(pipeline);
                    RenderSystem.bindDefaultUniforms(renderPass);
                    renderPass.setUniform("DynamicTransforms", gpuBufferSlice);
                    renderPass.setVertexBuffer(0, vertexBuffer);
                    renderPass.setIndexBuffer(gpuBuffer, autoStorageIndexBuffer.type());
                    renderPass.bindTexture("InSampler", from, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                    renderPass.drawIndexed(0, 0, 6, 1);
                }
            }
        } finally {
            RenderSystem.setProjectionMatrix(oldProjectionMatrix, oldProjectionType);
            modelViewStack.popMatrix();
        }
    }
}
