package com.leclowndu93150.foxy.mixin.sodium;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlTexelBuffer;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sodium 0.9 added a {@code GpuBufferSlice} and a {@code GlTexelBuffer} to
 * {@code ShaderChunkRenderer.begin}, carrying the chunk uniforms it moved into a UBO and a texel
 * buffer. Voxy 0.2.16's {@code MixinDefaultChunkRenderer} still calls the three-argument form —
 * from {@code voxy$cancelThingie}, the {@code @Inject(method = "render", at = @At("HEAD"),
 * cancellable = true)} handler that gets copied into {@code DefaultChunkRenderer} verbatim — which
 * would fail at runtime with {@code NoSuchMethodError}. (Voxy's {@code doRender}, despite the name,
 * never calls {@code begin}/{@code end} at all; it only does Voxy's own viewport setup and
 * {@code renderOpaque} dispatch. Confirmed by decompiling the real 0.2.16-beta jar.)
 *
 * <p>Nothing is fabricated: {@code render} already receives both values, so they are captured
 * there and handed straight back to Sodium.
 *
 * <p>Priority 2000 so this applies after Voxy's mixin, which is what adds {@code voxy$cancelThingie}
 * (and {@code doRender}) to {@code DefaultChunkRenderer}.
 */
@Mixin(value = DefaultChunkRenderer.class, priority = 2000, remap = false)
public abstract class FoxyMixinDefaultChunkRenderer {

    @Shadow
    protected abstract void begin(TerrainRenderPass pass, FogParameters fog, GpuSampler sampler,
                                  GpuBufferSlice uniforms, GlTexelBuffer texels);

    @Unique
    private GpuBufferSlice foxy$uniforms;

    @Unique
    private GlTexelBuffer foxy$texels;

    @Inject(method = "render", at = @At("HEAD"))
    private void foxy$captureUniformBuffers(ChunkRenderMatrices matrices, CommandList commandList,
                                            ChunkRenderListIterable lists, TerrainRenderPass pass,
                                            CameraTransform camera, FogParameters fog,
                                            boolean sorted, GpuSampler sampler,
                                            GpuBufferSlice uniforms, GlTexelBuffer texels,
                                            CallbackInfo ci) {
        this.foxy$uniforms = uniforms;
        this.foxy$texels = texels;
    }

    @Redirect(
            method = "voxy$cancelThingie",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;"
                            + "begin(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;"
                            + "Lnet/caffeinemc/mods/sodium/client/util/FogParameters;"
                            + "Lcom/mojang/blaze3d/textures/GpuSampler;)V"))
    private void foxy$forwardBegin(ShaderChunkRenderer self, TerrainRenderPass pass,
                                   FogParameters fog, GpuSampler sampler) {
        this.begin(pass, fog, sampler, this.foxy$uniforms, this.foxy$texels);
    }
}
