package com.github.kevindagame.voxelsniperforge.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.NotNull;

/**
 * Converts between Adventure components (used by VoxelSniper Core) and Minecraft components, through their shared
 * JSON format. Since 1.20.5 the Minecraft side needs registry access to (de)serialize hover items and the like.
 */
public final class ComponentConverter {
    private ComponentConverter() {
    }

    private static HolderLookup.Provider registries() {
        var server = ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.registryAccess() : RegistryAccess.EMPTY;
    }

    public static MutableComponent toNative(@NotNull Component component) {
        if (component == Component.empty()) return net.minecraft.network.chat.Component.empty();
        var result = net.minecraft.network.chat.Component.Serializer.fromJson(GsonComponentSerializer.gson().serialize(component), registries());
        return result != null ? result : net.minecraft.network.chat.Component.empty();
    }

    public static Component fromNative(@NotNull net.minecraft.network.chat.Component component) {
        return GsonComponentSerializer.gson().deserialize(net.minecraft.network.chat.Component.Serializer.toJson(component, registries()));
    }
}
