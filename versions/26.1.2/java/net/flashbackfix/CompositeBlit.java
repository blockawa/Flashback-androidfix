package net.flashbackfix;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
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
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Full-screen blit of the imgui overlay onto the composite, through a pipeline
 * with straight-alpha blending. Flashback 0.43.3's BLIT_SCREEN_WITH_UV declares no
 * color target state, which defaults to replace, so its blitTo would wipe the game
 * frame underneath. Flashback 26.2 fixed the same problem upstream by adding
 * premultiplied blending to that pipeline; blit_screen_old.fsh outputs straight
 * alpha, so this uses SRC_ALPHA blending instead.
 */
public final class CompositeBlit {

    private static RenderPipeline blendedBlit;
    private static ProjectionMatrixBuffer projectionBuffers;
    private static Projection projection;

    private CompositeBlit() {
    }

    private static RenderPipeline pipeline() {
        if (blendedBlit == null) {
            blendedBlit = RenderPipelines.register(
                    RenderPipeline.builder()
                            .withLocation(Identifier.fromNamespaceAndPath("flashbackandroidfix", "pipeline/overlay_blit"))
                            .withVertexShader(Identifier.fromNamespaceAndPath("flashback", "core/blit_screen_old"))
                            .withFragmentShader(Identifier.fromNamespaceAndPath("flashback", "core/blit_screen_old"))
                            .withSampler("InSampler")
                            .withDepthStencilState(Optional.empty())
                            .withCull(false)
                            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                            .withVertexFormat(DefaultVertexFormat.POSITION_TEX, Mode.QUADS)
                            .build());
        }
        return blendedBlit;
    }

    public static void blitOverlay(GpuTextureView from, RenderTarget to, int width, int height) {
        if (projectionBuffers == null) {
            projectionBuffers = new ProjectionMatrixBuffer("flashbackandroidfix overlay blit");
            projection = new Projection();
            projection.setupOrtho(1000.0F, 3000.0F, width, height, true);
        } else if (projection.width() != width || projection.height() != height) {
            projection.setSize(width, height);
        }

        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix();
        modelViewStack.set(new Matrix4f().translation(0.0F, 0.0F, -2000.0F));
        GpuBufferSlice oldProjectionMatrix = RenderSystem.getProjectionMatrixBuffer();
        ProjectionType oldProjectionType = RenderSystem.getProjectionType();
        RenderSystem.setProjectionMatrix(projectionBuffers.getBuffer(projection), ProjectionType.ORTHOGRAPHIC);
        BufferBuilder builder = Tesselator.getInstance().begin(Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        builder.addVertex(0.0F, height, 0.0F).setUv(0.0F, 0.0F);
        builder.addVertex(width, height, 0.0F).setUv(1.0F, 0.0F);
        builder.addVertex(width, 0.0F, 0.0F).setUv(1.0F, 1.0F);
        builder.addVertex(0.0F, 0.0F, 0.0F).setUv(0.0F, 1.0F);
        MeshData meshData = builder.buildOrThrow();

        try {
            AutoStorageIndexBuffer autoStorageIndexBuffer = RenderSystem.getSequentialBuffer(Mode.QUADS);
            GpuBuffer gpuBuffer = autoStorageIndexBuffer.getBuffer(6);
            GpuBuffer vertexBuffer = DefaultVertexFormat.POSITION_TEX.uploadImmediateVertexBuffer(meshData.vertexBuffer());
            GpuBufferSlice gpuBufferSlice = RenderSystem.getDynamicUniforms()
                    .writeTransform(RenderSystem.getModelViewMatrix(), new Vector4f(1.0F, 1.0F, 1.0F, 1.0F), new Vector3f(), new Matrix4f());
            RenderPass renderPass = RenderSystem.getDevice()
                    .createCommandEncoder()
                    .createRenderPass(() -> "flashbackandroidfix overlay blit", to.getColorTextureView(), OptionalInt.empty());

            try {
                renderPass.setPipeline(pipeline());
                RenderSystem.bindDefaultUniforms(renderPass);
                renderPass.setUniform("DynamicTransforms", gpuBufferSlice);
                renderPass.setVertexBuffer(0, vertexBuffer);
                renderPass.setIndexBuffer(gpuBuffer, autoStorageIndexBuffer.type());
                renderPass.bindTexture("InSampler", from, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                renderPass.drawIndexed(0, 0, 6, 1);
            } catch (Throwable t) {
                try {
                    renderPass.close();
                } catch (Throwable suppressed) {
                    t.addSuppressed(suppressed);
                }
                throw t;
            }
            renderPass.close();
        } catch (Throwable t) {
            try {
                meshData.close();
            } catch (Throwable suppressed) {
                t.addSuppressed(suppressed);
            }
            throw t;
        }
        meshData.close();

        RenderSystem.setProjectionMatrix(oldProjectionMatrix, oldProjectionType);
        modelViewStack.popMatrix();
    }
}
