package com.github.kevindagame.voxelsniperforge.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Regenerates one loaded chunk in place with the level's own chunk generator.
 * <p>
 * Minecraft 1.21 removed {@code ResetChunksCommand}, which the 1.20 Forge port copied. This is the same idea on the
 * 1.21 chunk step API: clear the chunk, then run the BIOMES to FEATURES generation steps against the live chunk
 * (wrapped in a writable {@link ImposterProtoChunk}), with the surrounding chunks loaded from the chunk source.
 * Structure starts and references already in the chunk are kept, so terrain adapts to structures like on first
 * generation. Light is rechecked where blocks changed and the chunks are resent to players.
 */
final class ChunkRegenerator {
    private static final List<ChunkStatus> STEPS = List.of(
            ChunkStatus.BIOMES, ChunkStatus.NOISE, ChunkStatus.SURFACE, ChunkStatus.CARVERS, ChunkStatus.FEATURES);

    private ChunkRegenerator() {
    }

    static void regenerate(ServerLevel level, int chunkX, int chunkZ) {
        var chunkSource = level.getChunkSource();
        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
        ChunkPos pos = chunk.getPos();

        // Snapshot the old blocks so light is only rechecked where something changed.
        LevelChunkSection[] sections = chunk.getSections();
        BlockState[][] before = new BlockState[sections.length][];
        for (int i = 0; i < sections.length; i++) {
            before[i] = snapshot(sections[i]);
        }

        // Clear the chunk without block updates, drops or neighbour reactions.
        for (BlockPos bePos : new ArrayList<>(chunk.getBlockEntities().keySet())) {
            chunk.removeBlockEntity(bePos);
        }
        BlockState air = Blocks.AIR.defaultBlockState();
        for (LevelChunkSection section : sections) {
            if (section.hasOnlyAir()) continue;
            section.acquire();
            try {
                for (int y = 0; y < 16; y++)
                    for (int z = 0; z < 16; z++)
                        for (int x = 0; x < 16; x++)
                            section.setBlockState(x, y, z, air, false);
            } finally {
                section.release();
            }
        }

        ImposterProtoChunk center = new ImposterProtoChunk(chunk, true);
        // The main thread mailbox is only used by the FULL step, which is not run here.
        WorldGenContext context = new WorldGenContext(level, chunkSource.getGenerator(), level.getStructureManager(),
                chunkSource.getLightEngine(), null);
        Set<ChunkPos> touched = new HashSet<>();
        touched.add(pos);

        for (ChunkStatus status : STEPS) {
            ChunkStep step = ChunkPyramid.GENERATION_PYRAMID.getStepTo(status);
            int radius = step.directDependencies().size() - 1;
            // Neighbours are loaded here, on the server thread, up to the status the step needs at their distance.
            // Generation tasks may run on worker threads and must not load chunks themselves.
            StaticCache2D<GenerationChunkHolder> cache = StaticCache2D.create(chunkX, chunkZ, Math.max(radius, 0), (x, z) -> {
                if (x == chunkX && z == chunkZ) return new FixedHolder(pos, center);
                int distance = Math.max(Math.abs(x - chunkX), Math.abs(z - chunkZ));
                ChunkAccess access = chunkSource.getChunk(x, z, step.directDependencies().get(distance), true);
                if (access instanceof LevelChunk levelChunk) {
                    // Features may spill into direct neighbours; let them write to the live chunk.
                    boolean writable = distance <= step.blockStateWriteRadius();
                    if (writable) touched.add(levelChunk.getPos());
                    access = new ImposterProtoChunk(levelChunk, writable);
                }
                return new FixedHolder(new ChunkPos(x, z), access);
            });
            CompletableFuture<ChunkAccess> future = step.apply(context, cache, center);
            level.getServer().managedBlock(future::isDone);
            future.join();
        }

        Heightmap.primeHeightmaps(chunk, EnumSet.allOf(Heightmap.Types.class));
        chunk.setUnsaved(true);
        relight(level, chunk, before);

        ThreadedLevelLightEngine lightEngine = chunkSource.getLightEngine();
        for (ChunkPos changed : touched) {
            LevelChunk c = level.getChunkSource().getChunkNow(changed.x, changed.z);
            if (c == null) continue;
            c.setUnsaved(true);
            var packet = new ClientboundLevelChunkWithLightPacket(c, lightEngine, null, null);
            for (ServerPlayer player : chunkSource.chunkMap.getPlayers(changed, false)) {
                player.connection.send(packet);
            }
        }
    }

    private static BlockState @Nullable [] snapshot(LevelChunkSection section) {
        if (section.hasOnlyAir()) return null;
        BlockState[] states = new BlockState[4096];
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    states[(y << 8) | (z << 4) | x] = section.getBlockState(x, y, z);
        return states;
    }

    private static void relight(ServerLevel level, LevelChunk chunk, BlockState[][] before) {
        var lightEngine = level.getChunkSource().getLightEngine();
        BlockState air = Blocks.AIR.defaultBlockState();
        LevelChunkSection[] sections = chunk.getSections();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int i = 0; i < sections.length; i++) {
            int sectionY = chunk.getSectionYFromSectionIndex(i);
            BlockState[] old = before[i];
            LevelChunkSection section = sections[i];
            lightEngine.updateSectionStatus(SectionPos.of(chunk.getPos(), sectionY), section.hasOnlyAir());
            if (old == null && section.hasOnlyAir()) continue;
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        BlockState was = old == null ? air : old[(y << 8) | (z << 4) | x];
                        BlockState now = section.getBlockState(x, y, z);
                        if (was != now) {
                            lightEngine.checkBlock(mutable.set(chunk.getPos().getBlockX(x), SectionPos.sectionToBlockCoord(sectionY) + y, chunk.getPos().getBlockZ(z)));
                        }
                    }
        }
    }

    /** Serves one already loaded chunk for every status. */
    private static final class FixedHolder extends GenerationChunkHolder {
        private final ChunkAccess chunk;

        FixedHolder(ChunkPos pos, ChunkAccess chunk) {
            super(pos);
            this.chunk = chunk;
        }

        @Override
        public ChunkAccess getChunkIfPresentUnchecked(ChunkStatus status) {
            return chunk;
        }

        @Override
        public ChunkStatus getPersistedStatus() {
            return chunk.getPersistedStatus();
        }

        @Override
        public int getTicketLevel() {
            return 0;
        }

        @Override
        public int getQueueLevel() {
            return 0;
        }
    }
}
