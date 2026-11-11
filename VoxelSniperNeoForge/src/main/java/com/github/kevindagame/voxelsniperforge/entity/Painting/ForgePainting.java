package com.github.kevindagame.voxelsniperforge.entity.Painting;

import com.github.kevindagame.voxelsniper.entity.painting.IPainting;
import com.github.kevindagame.voxelsniperforge.entity.ForgeEntity;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.decoration.PaintingVariant;

import java.util.Comparator;
import java.util.List;

public class ForgePainting extends ForgeEntity implements IPainting {

    public ForgePainting(Painting entity) {
        super(entity);
    }

    private Painting painting() {
        return (Painting) this.getEntity();
    }

    /**
     * Painting variants are a data driven registry since 1.21, so the list comes from the level's registry access.
     * Sorted by id so the numbering is stable across restarts.
     */
    private List<Holder.Reference<PaintingVariant>> variants() {
        return painting().level().registryAccess().registryOrThrow(Registries.PAINTING_VARIANT).holders()
                .sorted(Comparator.comparing(h -> h.key().location().toString()))
                .toList();
    }

    @Override
    public boolean setArtId(int id) {
        var variants = variants();
        if (id < 0 || id >= variants.size())
            return false;
        painting().setVariant(variants.get(id));
        return true;
    }

    @Override
    public int getArtId() {
        var current = painting().getVariant();
        var variants = variants();
        for (int i = 0; i < variants.size(); i++) {
            if (current.is(variants.get(i).key())) return i;
        }
        return 0;
    }

    @Override
    public int nextPaintingId(boolean back) {
        int size = variants().size();
        return (this.getArtId() + (back ? -1 : 1) + size) % size;
    }
}
