package net.flashbackfix;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.moulberry.flashback.FramebufferUtils;
import com.moulberry.flashback.editor.ui.ReplayUI;
import imgui.moulberry90.ImDrawData;
import imgui.moulberry90.ImFontAtlas;
import imgui.moulberry90.ImVec2;
import imgui.moulberry90.ImVec4;
import imgui.moulberry90.type.ImInt;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Port of Flashback 26.2's CustomImGuiImplB3D to the 26.1.2 blaze3d API.
 * Renders imgui draw data into an offscreen framebuffer through a RenderPipeline
 * instead of the legacy immediate-mode GL3 backend, which does not work under
 * MobileGlues.
 */
public final class ImGuiB3DRenderer {

    /**
     * ImDrawVert is position(2 float) + uv(2 float) + colour(4 ubyte) = 20 bytes.
     * 26.1.2 has no built-in 2-float element, so register one. Vanilla only uses
     * element ids 0-6 and nothing else in the game jar calls register.
     */
    private static final VertexFormatElement POSITION_FLOAT2 =
            VertexFormatElement.register(7, 0, VertexFormatElement.Type.FLOAT, false, 2);

    private static ImGuiB3DRenderer instance;

    /** Offscreen target holding last frame's imgui output; consumed by ImguiPresentFix. */
    public static RenderTarget lastComposite = null;

    public static ImGuiB3DRenderer get() {
        if (instance == null) {
            instance = new ImGuiB3DRenderer();
        }
        return instance;
    }

    private ImGuiB3DRenderer() {
    }

    private GpuSampler samplerLinear;
    private GpuSampler samplerNearest;
    private GpuBuffer uniforms;
    private ByteBuffer uniformsHost;
    private float[] lastUniformFloats;
    private float[] nextUniformFloats;
    private RenderPipeline renderPipeline;

    private GpuBuffer vertexBuffer = null;
    private GpuBuffer indexBuffer = null;

    private ByteBuffer vertexBufferHost = null;
    private ByteBuffer indexBufferHost = null;

    private final Object2IntOpenHashMap<GpuTextureView> viewToIndex = new Object2IntOpenHashMap<>();
    private final List<GpuTextureView> indexToView = new ArrayList<>();
    private final IntList indexGaps = new IntArrayList();
    private final IntSet nearestTextures = new IntOpenHashSet();

    private RenderTarget renderTarget = null;
    private GpuTexture fontTexture = null;

    public void init() {
        GpuDevice device = RenderSystem.getDevice();

        if (this.uniforms == null) {
            this.uniforms = device.createBuffer(
                    () -> "Flashback ImGui Uniforms",
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM, 16 * 4);
            this.uniformsHost = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder());
            this.lastUniformFloats = new float[16];
            this.nextUniformFloats = new float[16];
        }

        if (this.samplerLinear == null) {
            this.samplerLinear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            this.samplerNearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        }

        if (this.renderPipeline == null) {
            VertexFormat vertexFormat = VertexFormat.builder()
                    .add("Position", POSITION_FLOAT2)
                    .add("UV", VertexFormatElement.UV0)
                    .add("Color", VertexFormatElement.COLOR)
                    .build();
            this.renderPipeline = RenderPipelines.register(
                    RenderPipeline.builder()
                            .withLocation(Identifier.fromNamespaceAndPath("flashback", "pipeline/imgui"))
                            .withVertexShader(Identifier.fromNamespaceAndPath("flashback", "core/imgui_b3d"))
                            .withFragmentShader(Identifier.fromNamespaceAndPath("flashback", "core/imgui_b3d"))
                            .withSampler("Sampler0")
                            .withDepthStencilState(Optional.empty())
                            .withCull(false)
                            .withUniform("UBO", UniformType.UNIFORM_BUFFER)
                            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                            .withVertexFormat(vertexFormat, VertexFormat.Mode.TRIANGLES)
                            .build());
        }

        this.updateFontsTexture();
    }

    public long getTextureId(GpuTextureView gpuTextureView) {
        if (gpuTextureView.isClosed()) {
            return 0;
        }

        int existing = this.viewToIndex.getOrDefault(gpuTextureView, -1);
        if (existing >= 0) {
            return existing + 1;
        }

        int nextIndex;
        if (!this.indexGaps.isEmpty()) {
            nextIndex = this.indexGaps.removeInt(this.indexGaps.size() - 1);
            this.indexToView.set(nextIndex, gpuTextureView);
        } else {
            nextIndex = this.indexToView.size();
            this.indexToView.add(gpuTextureView);
        }

        this.viewToIndex.put(gpuTextureView, nextIndex);
        return nextIndex + 1;
    }

    public void setSampleLinear(long id) {
        this.nearestTextures.remove((int) id);
    }

    public void setSampleNearest(long id) {
        this.nearestTextures.add((int) id);
    }

    public void updateFontsTexture() {
        if (this.fontTexture != null) {
            this.fontTexture.close();
            this.fontTexture = null;
        }

        final ImFontAtlas fontAtlas = ReplayUI.getIO().getFonts();
        final ImInt width = new ImInt();
        final ImInt height = new ImInt();
        final ByteBuffer buffer = fontAtlas.getTexDataAsRGBA32(width, height);

        GpuDevice device = RenderSystem.getDevice();
        this.fontTexture = device.createTexture(
                "Flashback ImGui Font Atlas",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                TextureFormat.RGBA8,
                width.intValue(),
                height.intValue(),
                1,
                1
        );
        device.createCommandEncoder().writeToTexture(
                this.fontTexture, buffer, NativeImage.Format.RGBA,
                0, 0, 0, 0, width.intValue(), height.intValue());
        GpuTextureView view = device.createTextureView(this.fontTexture);

        fontAtlas.setTexID(this.getTextureId(view));
    }

    private void cleanupUnusedTextures() {
        for (int i = 0; i < this.indexToView.size(); i++) {
            GpuTextureView view = this.indexToView.get(i);
            if (view == null || !view.isClosed()) {
                continue;
            }

            this.indexGaps.add(i);
            this.indexToView.set(i, null);
        }
    }

    public RenderTarget renderDrawData(final ImDrawData drawData) {
        int fbWidth = (int) (drawData.getDisplaySizeX() * drawData.getFramebufferScaleX());
        int fbHeight = (int) (drawData.getDisplaySizeY() * drawData.getFramebufferScaleY());

        if (fbWidth <= 0 || fbHeight <= 0 || drawData.getCmdListsCount() <= 0) {
            return null;
        }

        var device = RenderSystem.getDevice();
        var encoder = device.createCommandEncoder();
        int drawVertSize = ImDrawData.sizeOfImDrawVert();
        int drawIdxSize = ImDrawData.sizeOfImDrawIdx();
        long vertexBytes = (long) drawData.getTotalVtxCount() * drawVertSize;
        long indexBytes = (long) drawData.getTotalIdxCount() * drawIdxSize;

        if (vertexBytes >= Integer.MAX_VALUE) {
            throw new Error("vertex bytes >= max integer: " + vertexBytes);
        }
        if (indexBytes >= Integer.MAX_VALUE) {
            throw new Error("index bytes >= max integer: " + indexBytes);
        }

        int cmdListCount = drawData.getCmdListsCount();

        // Put all vertex buffer data into single contiguous host array
        if (this.vertexBufferHost == null || this.vertexBufferHost.capacity() < vertexBytes) {
            this.vertexBufferHost = ByteBuffer.allocateDirect((int) vertexBytes + 4096).order(ByteOrder.nativeOrder());
        }
        this.vertexBufferHost.clear();
        for (int i = 0; i < cmdListCount; i++) {
            this.vertexBufferHost.put(drawData.getCmdListVtxBufferData(i));
        }
        this.vertexBufferHost.flip();

        if (this.vertexBufferHost.limit() != vertexBytes) {
            throw new Error("host limit != vertexBytes: " + this.vertexBufferHost.limit() + " != " + vertexBytes);
        }

        // Upload vertexBufferHost to vertexBuffer
        if (this.vertexBuffer != null && this.vertexBuffer.size() < vertexBytes) {
            this.vertexBuffer.close();
            this.vertexBuffer = null;
        }
        if (this.vertexBuffer == null) {
            this.vertexBuffer = device.createBuffer(
                    () -> "Flashback ImGui Vtx",
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_VERTEX,
                    vertexBytes + 4096
            );
        }
        encoder.writeToBuffer(this.vertexBuffer.slice(0, vertexBytes), this.vertexBufferHost);

        // Put all index buffer data into single contiguous host array
        if (this.indexBufferHost == null || this.indexBufferHost.capacity() < indexBytes) {
            this.indexBufferHost = ByteBuffer.allocateDirect((int) indexBytes + 8192).order(ByteOrder.nativeOrder());
        }
        this.indexBufferHost.clear();
        for (int i = 0; i < cmdListCount; i++) {
            this.indexBufferHost.put(drawData.getCmdListIdxBufferData(i));
        }
        this.indexBufferHost.flip();

        if (this.indexBufferHost.limit() != indexBytes) {
            throw new Error("host limit != indexBytes: " + this.indexBufferHost.limit() + " != " + indexBytes);
        }

        // Upload indexBufferHost to indexBuffer
        if (this.indexBuffer != null && this.indexBuffer.size() < indexBytes) {
            this.indexBuffer.close();
            this.indexBuffer = null;
        }
        if (this.indexBuffer == null) {
            this.indexBuffer = device.createBuffer(
                    () -> "Flashback ImGui Idx",
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_INDEX,
                    indexBytes + 8192
            );
        }
        encoder.writeToBuffer(this.indexBuffer.slice(0, indexBytes), this.indexBufferHost);

        this.setupUniforms(drawData, device, encoder);

        this.renderTarget = FramebufferUtils.resizeOrCreateFramebuffer(this.renderTarget, fbWidth, fbHeight, false);
        Objects.requireNonNull(this.renderTarget.getColorTextureView());

        try (RenderPass renderPass = encoder.createRenderPass(
                () -> "Flashback ImGui Render Pass",
                this.renderTarget.getColorTextureView(),
                OptionalInt.of(0))
        ) {
            VertexFormat.IndexType indexType;
            if (drawIdxSize == 2) {
                indexType = VertexFormat.IndexType.SHORT;
            } else {
                indexType = VertexFormat.IndexType.INT;
            }

            renderPass.setPipeline(this.renderPipeline);
            renderPass.setUniform("UBO", this.uniforms);
            renderPass.setVertexBuffer(0, this.vertexBuffer);
            renderPass.setIndexBuffer(this.indexBuffer, indexType);

            ImVec2 clipOffset = drawData.getDisplayPos();
            ImVec2 clipScale = drawData.getFramebufferScale();

            long last = -1;
            int globalVertexOffset = 0;
            int globalIndexOffset = 0;

            for (int cmdList = 0; cmdList < cmdListCount; cmdList++) {
                int cmdBufferSize = drawData.getCmdListCmdBufferSize(cmdList);
                for (int cmdBuf = 0; cmdBuf < cmdBufferSize; cmdBuf++) {
                    long textureId = drawData.getCmdListCmdBufferTextureId(cmdList, cmdBuf);

                    if (textureId > 0 && textureId != last) {
                        last = textureId;

                        GpuTextureView view = null;
                        int registryIndex = (int) textureId - 1;
                        if (registryIndex >= 0 && registryIndex < this.indexToView.size()) {
                            view = this.indexToView.get(registryIndex);
                        }
                        if (view != null && !view.isClosed()) {
                            var sampler = this.samplerLinear;
                            if (this.nearestTextures.contains((int) textureId)) {
                                sampler = this.samplerNearest;
                            }
                            renderPass.bindTexture("Sampler0", view, sampler);
                        }
                    }

                    ImVec4 clipRect = drawData.getCmdListCmdBufferClipRect(cmdList, cmdBuf);

                    int clipMinX = (int) ((clipRect.x - clipOffset.x) * clipScale.x);
                    int clipMinY = (int) ((clipRect.y - clipOffset.y) * clipScale.y);
                    int clipMaxX = (int) ((clipRect.z - clipOffset.x) * clipScale.x);
                    int clipMaxY = (int) ((clipRect.w - clipOffset.y) * clipScale.y);

                    clipMinX = Math.max(clipMinX, 0);
                    clipMaxX = Math.min(clipMaxX, fbWidth);
                    clipMinY = Math.max(clipMinY, 0);
                    clipMaxY = Math.min(clipMaxY, fbHeight);

                    if (clipMaxX < clipMinX || clipMaxY < clipMinY) {
                        continue;
                    }

                    if (clipMinX == 0 && clipMaxX == fbWidth && clipMinY == 0 && clipMaxY == fbHeight) {
                        renderPass.disableScissor();
                    } else {
                        renderPass.enableScissor(clipMinX, fbHeight - clipMaxY, clipMaxX - clipMinX, clipMaxY - clipMinY);
                    }

                    int indexCount = drawData.getCmdListCmdBufferElemCount(cmdList, cmdBuf);
                    int firstIndex = drawData.getCmdListCmdBufferIdxOffset(cmdList, cmdBuf) + globalIndexOffset;
                    int firstVertex = drawData.getCmdListCmdBufferVtxOffset(cmdList, cmdBuf) + globalVertexOffset;
                    renderPass.drawIndexed(firstVertex, firstIndex, indexCount, 1);
                }

                globalVertexOffset += drawData.getCmdListVtxBufferSize(cmdList);
                globalIndexOffset += drawData.getCmdListIdxBufferSize(cmdList);
            }
        }

        this.cleanupUnusedTextures();

        lastComposite = this.renderTarget;
        return this.renderTarget;
    }

    private void setupUniforms(final ImDrawData drawData, GpuDevice device, CommandEncoder encoder) {
        float l = drawData.getDisplayPosX();
        float r = drawData.getDisplayPosX() + drawData.getDisplaySizeX();
        float t = drawData.getDisplayPosY();
        float b = drawData.getDisplayPosY() + drawData.getDisplaySizeY();

        if (device.isZZeroToOne()) {
            this.nextUniformFloats[10] = 0.5f;
            this.nextUniformFloats[14] = 0.5f;
        } else {
            this.nextUniformFloats[10] = 1.0f;
            this.nextUniformFloats[14] = 0.0f;
        }
        this.nextUniformFloats[15] = 1.0f;

        this.nextUniformFloats[0] = 2.0f / (r - l);
        this.nextUniformFloats[5] = 2.0f / (t - b);
        this.nextUniformFloats[12] = (r + l) / (l - r);
        this.nextUniformFloats[13] = (t + b) / (b - t);

        // note: might need to account for gamma if render target format changes

        if (!Arrays.equals(this.lastUniformFloats, this.nextUniformFloats)) {
            float[] temp = this.lastUniformFloats;
            this.lastUniformFloats = this.nextUniformFloats;
            this.nextUniformFloats = temp;

            this.uniformsHost.clear();
            this.uniformsHost.asFloatBuffer().put(this.lastUniformFloats);
            this.uniformsHost.position(0);
            this.uniformsHost.limit(64);

            encoder.writeToBuffer(this.uniforms.slice(), this.uniformsHost);
        }
    }
}
