package com.leclowndu93150.foxy.mixin.sodium;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.mixin.sodium.AccessorChunkTracker;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Replaces Voxy 0.2.16's redirect of {@code RenderSection.setInfo}.
 *
 * <p>Sodium 0.9 changed that method's return from {@code boolean} to an {@code int} bitmask —
 * bit 0 for "flags or visibility data changed", bit 1 for "section has culled block entities or
 * animated sprites" — and {@code updateSectionInfo} feeds it into {@code renderableSectionTree}
 * membership and {@code sectionsWithGlobalEntities}. Voxy's handler only tests the value it gets
 * and then returns a synthesised {@code true}/{@code false}, so re-typing it would clear bit 1
 * on every path: animated sprites would never register for ticking and culled block-entity
 * tracking would silently drift. {@code VoxySodiumCompatPatch} strips Voxy's annotation and this
 * redirect takes over, preserving the mask while keeping Voxy's ingest behaviour — a faithful
 * port of the stripped handler's logic, reconstructed from its bytecode, with the return value
 * corrected. The mapping to the original is exact: 0.8 {@code false} is 0.9 {@code 0}, and 0.8
 * {@code true} is any non-zero mask.
 *
 * <p>Voxy's other three injectors on this class ({@code <init>}, {@code onChunkAdded},
 * {@code onChunkRemoved}) target methods 0.9 did not change and are left alone. Priority 2000 so
 * this applies after them.
 *
 * <p>Three deliberate divergences from Voxy's original: the render system is reached through
 * {@code Minecraft.getInstance().levelRenderer} (public) rather than
 * {@code ClientLevel.levelRenderer}, which is private and only readable by Voxy through its
 * access widener — same object on the client, where this code exclusively runs.
 * {@code foxy$bottomSectionY} is derived from {@code level.getMinY() >> 4} on first use rather
 * than shadowing the field Voxy's mixin adds in its constructor injection, which would couple
 * this mixin to Voxy's application order. And the section-flags reads go through
 * {@code getRegion().getSectionFlags(getSectionIndex())}: Sodium 0.9 removed
 * {@code RenderSection.getFlags()} and moved the flags into {@code RenderRegion}, which is
 * where 0.9's own {@code setRenderState} reads them.
 */
@Mixin(value = RenderSectionManager.class, priority = 2000, remap = false)
public abstract class FoxyMixinRenderSectionManager {

    @Shadow
    @Final
    private ClientLevel level;

    @Unique
    private int foxy$bottomSectionY = Integer.MIN_VALUE;

    @Unique
    private long foxy$cachedChunkPos = Long.MIN_VALUE;

    @Unique
    private int foxy$cachedChunkStatus;

    @Redirect(
            method = "updateSectionInfo",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;"
                            + "setInfo(Lnet/caffeinemc/mods/sodium/client/render/chunk/data/BuiltSectionInfo;)I"))
    private int foxy$updateOnUpload(RenderSection section, BuiltSectionInfo info) {
        boolean hadFlags = foxy$sectionFlags(section) != 0;
        int flags = foxy$sectionFlags(section);

        int result = section.setInfo(info);
        if (result == 0) {
            return result;
        }
        if (hadFlags == (foxy$sectionFlags(section) != 0)) {
            return result;
        }
        flags |= foxy$sectionFlags(section);
        if (flags == 0) {
            return result;
        }

        VoxyRenderSystem renderSystem =
                ((IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer)
                        .voxy$getRenderSystem();
        if (renderSystem == null) {
            return result;
        }

        int chunkX = section.getChunkX();
        int chunkY = section.getChunkY();
        int chunkZ = section.getChunkZ();

        if (hadFlags && VoxyConfig.CONFIG.ingestEnabled) {
            Long2IntOpenHashMap chunkStatus =
                    ((AccessorChunkTracker) ChunkTrackerHolder.get(this.level)).getChunkStatus();
            long chunkPos = ChunkPos.pack(chunkX, chunkZ);
            if (chunkPos != this.foxy$cachedChunkPos) {
                this.foxy$cachedChunkPos = chunkPos;
                this.foxy$cachedChunkStatus = chunkStatus.getOrDefault(chunkPos, 0);
            }
            if (this.foxy$cachedChunkStatus == 3) {
                LevelChunk chunk = this.level.getChunkSource()
                        .getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk != null) {
                    if (this.foxy$bottomSectionY == Integer.MIN_VALUE) {
                        this.foxy$bottomSectionY = this.level.getMinY() >> 4;
                    }
                    LevelChunkSection chunkSection =
                            chunk.getSection(chunkY - this.foxy$bottomSectionY);
                    LevelLightEngine lighting = this.level.getLightEngine();
                    SectionPos sectionPos = SectionPos.of(chunkX, chunkY, chunkZ);
                    DataLayer blockLight = lighting.getLayerListener(LightLayer.BLOCK)
                            .getDataLayerData(sectionPos);
                    DataLayer skyLight = lighting.getLayerListener(LightLayer.SKY)
                            .getDataLayerData(sectionPos);
                    VoxelIngestService.rawIngest(
                            renderSystem.getEngine(), chunkSection, chunkX, chunkY, chunkZ,
                            blockLight == null ? null : blockLight.copy(),
                            skyLight == null ? null : skyLight.copy());
                }
            }
        }

        long packed = SectionPos.asLong(chunkX, chunkY, chunkZ);
        if (hadFlags) {
            renderSystem.chunkBoundRenderer.removeSection(packed);
        } else {
            renderSystem.chunkBoundRenderer.addSection(packed);
        }
        return result;
    }

    /** Sodium 0.9's home for what 0.8 exposed as {@code RenderSection.getFlags()}. */
    @Unique
    private static int foxy$sectionFlags(RenderSection section) {
        return section.getRegion().getSectionFlags(section.getSectionIndex());
    }
}
