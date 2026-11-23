# Porting Clientmetics to every OneConfig version

Goal: one Clientmetics build per Minecraft version that OneConfig 1.9+ (and PolyCompose 3) ships
for. All of them are on OneConfig 1.9.3 and PolyCompose 3.0.6 (checked on
`repo.polyfrost.org`, 2026-11-23):

| Target | Loader | Java | Notes |
| --- | --- | --- | --- |
| 1.8.9 | Ornithe (Fabric Loader + OSL) | 25 here (8+ for the game) | Today's code: MCP names, GL1 display lists |
| 1.21.1 | Fabric | 21 | Entity render state not yet split out |
| 1.21.4 | Fabric | 21 | Entity render states (since 1.21.2) |
| 1.21.5 | Fabric | 21 | Render pipelines replace most `RenderSystem` state |
| 1.21.8 | Fabric | 21 | GUI rendering moved to render states (1.21.6) |
| 1.21.10 | Fabric | 21 | |
| 1.21.11 | Fabric | 21 | Last obfuscated release |
| 26.1, 26.2, 26.3 | Fabric | 25 | Unobfuscated; no intermediary remapping |

Modern versions use Mojang's official mappings. The notes in the table are from memory of the
release changelogs and need confirming against each version when it's ported, especially the
Java version and obfuscation for 26.x.

## Layout

```
core/            plain Java (no Minecraft, no loader): net/, asset/ catalogues and files,
                 cosmetic/ state and accounts, model/ parsing, Molang, animation, Config, Consent
                 logic, HostClients detection. Talks to the game only through `platform.Game`.
versions/1.8.9/  today's Minecraft-facing code: mixins, CosmeticRenderer, PreviewRenderer,
                 Textures, GL drawing of models, TermsNoticeScreen, entrypoint, autotests.
versions/modern/ one source set for 1.21.1 to 26.3, built once per version with Stonecutter
                 (`//? if >=1.21.5 {` comments) for the parts that differ.
compose/         the Kotlin/Compose wardrobe UI. Shared; its offscreen previews go through a
                 small platform interface so it never touches render code directly.
```

`core` compiles to Java 21 bytecode so the 1.21.x targets can use it.

## Order

1. Done: detect Lunar Client, Dawn Client and Essential (`HostClients`) and leave each one's
   cosmetics to it.
2. Put every game call the core makes behind `platform.Game` (session, join, world, nearby
   players), with the 1.8.9 implementation in `platform.LegacyGame`. 1.8.9 must behave the same
   (autotests pass).
3. Move the core into `core/` as a Gradle subproject, and the rest into `versions/1.8.9/`.
4. Split `GeoModel`/`ObjModel` into parsed data (core) and a per-version drawer: 1.8.9 keeps its
   display lists; modern versions build vertices into a `VertexConsumer` per frame (or a cached
   buffer).
5. First modern target, 1.21.11: entrypoint, `Game`, textures (`NativeImage` +
   `DynamicTexture`), a player feature renderer, cape and skin-layer hooks (through `PlayerSkin`),
   client commands through Fabric's API, terms screen, offscreen previews for the wardrobe.
6. Spread it across the other modern versions with Stonecutter, oldest (1.21.1) last since it
   differs most.
7. Build and dev-run every target in CI; the existing autotests become per-version smoke tests.

## Per-version differences to expect

- Capes: 1.20.2+ give each player a `PlayerSkin` (`SkinTextures` in Yarn); the cape is swapped
  there rather than through `getLocationCape`.
- Cosmetics layer: a `RenderLayer`/`FeatureRenderer` on the player renderer; from 1.21.2 it gets
  an entity render state instead of the entity, so per-player data (UUID, outfit, limb angles) has
  to be copied into the state with a mixin.
- Skin layers hidden by a cosmetic: `PlayerModelPart` handling moved into the render state too.
- Commands: Fabric's client command API instead of a chat mixin.
- Essential, Lunar and Dawn all run on modern Fabric, so `HostClients` matters more there: in
  Lunar Client Lunar cosmetics stay off, with Essential installed Essential stays off, and so on.
