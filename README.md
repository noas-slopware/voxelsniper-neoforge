# VoxelSniper Reimagined for NeoForge 1.21.1

A port of [VoxelSniper Reimagined](https://github.com/KevinDaGame/VoxelSniper-Reimagined) 8.14.0 by KevinDaGame
and contributors to NeoForge 1.21.1. It's the long-distance brush editor: hold an arrow or gunpowder, point at
something far away, click, and the brush paints, sculpts, grows trees or regenerates chunks there.

It runs on the server only. Players join with a vanilla or modded client and don't need it installed. Built and
tested against NeoForge 21.1.262; it should work on any 21.1.x.

AI-assisted port (Claude). Tested on a bare NeoForge server and on the New YuruMC Modded pack, not much beyond that.

## Download

Grab the jar from [Releases](https://github.com/noas-slopware/voxelsniper-neoforge/releases) and put it in the
server's `mods` folder. Config, language file and permissions end up in `config/voxelsniper/`.

## Using it

Same commands as upstream: `/b <brush>` picks a brush, `/b <size>` sets the size, `/v <block>` the material,
`/vr` the replace material, `/u` undoes, `/d` resets. With the arrow, right click paints and sneak + left click
picks the material of the block you're looking at (gunpowder does the same for the replace material). The brush
list and the rest are in the [upstream wiki](https://github.com/KevinDaGame/VoxelSniper-Reimagined/wiki).

Permissions go through NeoForge's permission API, so a permission mod (LuckPerms and friends) can hand out the
`voxelsniper.*` nodes. Without one, everything is for operators only.

## What changed from upstream

Upstream's NeoForge 1.21 branch (`feature/1.21`) was an unfinished refactor that didn't run. This repo finishes it:

- Builds with ModDevGradle. Core and its libraries are shaded and relocated into one jar; Gson, Guava and slf4j
  come from Minecraft.
- Mixins are registered, and chat and sign text conversion work again (it threw `NotImplementedException`).
- Clicks only use events that fire on the server. NeoForge's `LeftClickEmpty` and `RightClickEmpty` fire only on
  the client, so left clicks in the air come from a hook on the arm swing packet, like CraftBukkit does it.
- Worlds are looked up by dimension. They were keyed by `ServerLevel.toString()`, which is the same for every
  dimension, so sniping in the Nether edited the Overworld.
- Players keep their brush settings across respawns and leaving the End (those create a new `ServerPlayer`).
- Painting variants come from the 1.21 data-driven registry.
- The regenerate chunk brush works again. 1.21 removed `ResetChunksCommand`, which the old port copied, so it now
  runs the generation steps itself on the 1.21 chunk step API.
- Brush arguments survive `/reload`, cancelled left clicks don't leave ghost blocks on creative clients, and
  `/goto` style teleports reach the client.

## Building

Needs JDK 21.

```sh
./gradlew :VoxelSniperNeoForge:shadowJar
```

The jar lands in `output/`. The Spigot module from upstream is still here and untouched.

## License

LGPL-2.1, same as upstream. See [LICENSE](LICENSE).
