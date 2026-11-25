<div align="center">

<img src="versions/26.2/src/main/resources/assets/foxy/icon.png" width="256" alt="Foxy">

# Foxy

Loads Voxy on NeoForge.

</div>

Voxy is a Fabric LoD rendering mod. Foxy makes it run on NeoForge without modifying
Voxy itself: it reads the unmodified Voxy jar at runtime and bridges the Fabric APIs
it uses over to NeoForge equivalents.

## How it works

- Reads `fabric.mod.json` from the Voxy jar and generates a synthetic `neoforge.mods.toml`
  (name, description, authors, icon, mixin configs, version) so FML loads it. Mod ids are
  rewritten to NeoForge's stricter format, which has no hyphens, so `voxy-extra` loads as
  `voxy_extra`.
- Extracts Voxy's bundled `META-INF/jars` (RocksDB, LWJGL zstd/lmdb, lz4, xz, jedis) as
  game libraries.
- Applies Voxy's `voxy.accesswidener` live as a class processor, plus a small supplement
  for NeoForge-only access gaps.
- Ships minimal Fabric API stubs so Voxy's bytecode links; `FabricLoader` delegates to
  `ModList` / `FMLEnvironment`. The stubs live under `com.leclowndu93150.foxy.fabricstub`,
  and Voxy's `net/fabricmc` references are rewritten to point at them while the jar is
  patched. All mods share one class loader, so shipping a real `net.fabricmc` package would
  make every multiloader mod that probes for `FabricLoader` believe it was running on Fabric.
- Runs the Fabric entrypoints from common setup, where `Minecraft.getInstance()` already
  exists as it does on Fabric, and translates the
  `sodium:config_api_user` entrypoint into the mod property Sodium's NeoForge build reads,
  so mods keep their Sodium config page.
- Drops mixins whose target class is not installed. Fabric mods use mixins for optional
  integrations, but a `@Mixin` on a missing class fails the entire config, and these configs
  are `required` — so an integration with a mod that has no NeoForge build would otherwise
  take the whole mod down.
- Hands Voxy's LoD texture bakery the block state it is baking, and keeps the result inside the
  block. Voxy collects a model's parts through vanilla's context-free `collectParts`, and NeoForge
  models that build their geometry from the world — Dynamic Trees' branches read the trunk radius
  out of the state — are handed air by that overload and emit nothing, so the block bakes fully
  transparent and its LoD is a hole. The stub level Foxy passes instead reports air around the
  block, which is what makes a model close its geometry rather than leave the open ends it would
  normally hand to its neighbours. Quads that land wholly outside the block are clamped onto the
  face they were drawn beyond: a model wider than one block, like a Dynamic Trees thick trunk,
  draws its shell into its neighbours and would otherwise bake to nothing the bakery can see.
  Vanilla models are untouched — NeoForge's context-carrying overload forwards to the context-free
  one unless a model overrides it, and geometry already inside the block is left as it was built.
  The call is redirected wherever it ends up in the bakery, so a mod that wraps it with a mixin —
  Ecliptic Seasons does, for its seasonal LoD models — does not switch the fix off. A weighted
  model bakes its heaviest variant rather than a random one: the LoD has one texture per block
  state, so a roll that lands on Ecliptic Seasons' one-in-twenty spring flower otherwise puts a
  flower on every grass block in view.
- Turns a mod's Mod Menu entrypoint into NeoForge's `IConfigScreenFactory`, so its settings get
  the Config button in the mod list. Mod Menu is Fabric-only, and its two interfaces are stubbed
  next to the Fabric ones. Voxy and `voxy-extra` reach the same screen through Sodium's video
  settings, which the `sodium:config_api_user` entrypoint already covers; Voxy World Gen declares
  only the Mod Menu one and had no way in at all.
- Reimplements the `/voxy` command against NeoForge's command system.
- Registers the client commands of the other bridged mods as they are. Fabric types them against
  its own command source, so NeoForge's is made one, and the trees go onto NeoForge's client
  dispatcher unchanged. Voxy SeedGen's `/voxyseedgen fill` arrives this way.
- Lets mod features grow in Voxy SeedGen's LoD. SeedGen paints them into a stand-in world whose
  ground is stone all the way up and whose chunk-presence checks throw, so Dynamic Trees refused
  every spot and a tree was never drawn. The top of each column now carries the surface SeedGen
  draws there itself, and every chunk the stand-in is asked about is reported present.
- Adds a Chunky auto-ingest mixin targeting NeoForge's `NeoForgeWorld`, so chunks Chunky
  pre-generates reach Voxy's LoD store. Chunky loads them on the server and the client never
  receives them, which otherwise leaves the LoD with nothing to show for the run. Chunky has no
  NeoForge build for 26.2 yet, so there the mixin stays dormant until one ships.

Nothing about Voxy is hardcoded. Metadata, mixins, access wideners, and bundled jars are
all read from whatever Voxy jar is present, so Voxy updates do not require Foxy changes.

## Requirements

- Minecraft 26.2 or 26.1.2
- NeoForge
- Sodium 0.9.1
- Voxy 0.2.19-beta (placed in the mods folder). Modrinth publishes it for 26.2 only. For 26.1.2,
  build it from source: upstream's 26.1.2 port of 0.2.18 is the single commit `4643445e` on the
  `2612` branch, and it cherry-picks onto `dev` with two trivial conflicts.

## Building

```
./gradlew :26.2:build
./gradlew :26.1:build
```

## License

MIT. This does not relicense Voxy; Voxy remains All Rights Reserved and is not redistributed.
