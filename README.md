# Clientmetics

An Ornithe mod for Minecraft 1.8.9 that shows the Lunar Client cosmetics other players have equipped, so a non-Lunar player sees what Lunar users see. It only shows cosmetics players own and have equipped. It never unlocks anything.

## AI DISCLOSURE
this mod is almost entirely sloped, pr's are accepted to clean up the code
#

## How it works

- **Who wears what:** logs in to Lunar's authenticator with the game's own Mojang session, opens the game websocket, and sends `SubscribeV2` / `Unsubscribe` as players come in and out of range. `PlayerCosmeticsPushV2` replies and pushes fill a UUID → outfit map.
- **Assets:** nothing of Lunar's is bundled, and a Lunar install is optional.
  - **Catalogue:** read from `~/.lunarclient` if Lunar is installed. Otherwise the mod asks Lunar's launch API (`api.lunarclientprod.com/launcher/launch`) for the textures index, then downloads only the catalogue files (cosmetics list, model index, `hats.json`/`bodywear.json`, `functions.molang`, `jit_index`) into `config/clientmetics/remote`. Every file is SHA-1 checked. The request sends this mod's installation ID, the real OS and the current launcher version (read from Lunar's public update feed, because the API rejects older ones as `OUTDATED_LAUNCHER`), but no hardware ID. If the update feed is unreachable, the last known version is used. If Lunar still refuses the request, the last downloaded copy is used; if the API reports `OUTDATED_LAUNCHER`, update Lunar Client and use `catalogueSource: "auto"` or `"install"` to read the local catalogue.
  - **Models and textures:** downloaded one at a time as players wear them (launcher cache first if installed), checked against `jit_index`, and cached in `config/clientmetics/cache`.
- **Drawing:**
  - cloaks replace the vanilla cape texture;
  - OBJ hats and bodywear attach to the head or body bone, using the `player` transforms from `hats.json` / `bodywear.json`;
  - GeckoLib items (pets, wings, suits, shoes, GeckoLib hats) render with GeckoLib's conventions and a built-in Molang interpreter that also loads Lunar's `functions.molang`.
- **Honest client identity:** the handshake names this mod (User-Agent `Clientmetics/1.1.1`, listed as a Fabric mod). It sends the real OS/CPU and no Lunar launcher or client version, hardware ID or canary token. If Lunar refuses that, the mod shows nothing.

## Essential cosmetics

**On by default** (`essential`; turn it off to never contact Essential). Essential's 1.8.9 build is Legacy Forge, so it cannot be installed
next to this Ornithe mod — which means "let Essential draw them" isn't an option here. With
`essential` on, this mod signs in to Essential's own connection manager, asks it what nearby players
are wearing, and draws the result from Essential's asset bundles.

That is an unofficial integration: Essential neither documents nor permits it, and its source licence
forbids derivative works. Nothing of Essential's is bundled or copied, but expect it to break, and
expect it to be refused. If an Essential build is ever present, this mod stands down and lets
Essential render.

Essential's packet *classes* are published, and the socket's headers, framing and keep-alive follow
the same working implementation this author's ClientTag mod uses against the same endpoint. It is
still an unofficial client Essential can refuse or change: on `HTTP 404/410` the log says the client
version was rejected, the wardrobe tab says so, and nothing else is affected.

This mod never unlocks, buys or transfers cosmetics; it only shows what players already have equipped.

Mix changes in player and cape rendering remain compatible with Essential's own layers. Test
custom player scales, hidden layers and animation changes with Essential installed.

## Dawn Client

With `dawn` on (the default), the mod also shows Dawn Client (formerly Feather) cosmetics:

- **Who wears what:** signs in to Dawn's API with the game's own Mojang session, opens Dawn's gateway websocket and tracks nearby players on its `players.tracked` channel. Dawn replies with each Dawn user's equipped slots (slot to cosmetic slug); later equip changes arrive the same way. The server only reports what a player owns and has equipped.
- **Assets:** nothing of Dawn's is bundled. The catalogue and asset index are read from a Dawn install (`%APPDATA%\.dawn`) if there is one, otherwise downloaded from Dawn's public endpoints (no sign-in) into `config/clientmetics/dawn`. Models and textures are read from the install, or downloaded one at a time as players wear them, SHA-1 checked against the index and cached in `config/clientmetics/cache/dawn`.
- **Drawing:** hats, face items, back items, arm items, boots, wings, pets and auras are Bedrock/GeckoLib models; bones named `anchor_head`, `anchor_body`, `anchor_leftarm`... follow the player's parts. `.meta.json` rules are applied (`armorConstraints` hide an item while that armour slot is worn; `transforms` shift it). Cloaks replace the vanilla cape texture.

Not supported for Dawn:

- **AVIF textures.** Java has no AVIF decoder (Dawn ships a native one), so anything with only an `.avif` texture is skipped. That is most cloaks (301 of 323 at the time of writing) and most shields; nearly every model type uses WebP and works.
- Shields, trims, emotes, the `fly` animation clip, `hideDuringCombat`, and the few wings that have a texture but no model.

## Velo Client

With `velo` on (the default), the mod also shows Velo Client capes:

- **Who wears what:** Velo has no per-player lookup. Every Velo client downloads one worldwide list of online Velo users and their capes (`GET api.velo-client.com/v1/online`, no sign-in), and this mod does the same every 30 seconds while you're on a server. It never signs in to Velo or sends a heartbeat, so it never shows up as a Velo user. Players are matched by UUID only; Velo also falls back to the player's name, which on offline-mode servers hands a Velo user's cape to anyone using their name.
- **Assets:** `custom:<sha256>` capes (uploaded by the player) come from `GET /v1/cape/<sha>`; Store capes are looked up with `GET /v1/cosmetics/item?id=` and fetched by the hash it names. Velo's own cache (`%APPDATA%\VeloClient\cache`) is read first if Velo is installed, then `config/clientmetics/cache/velo`. Every file is SHA-256 checked and at most 12 MiB.
- **Drawing:** as in Velo, only the 22x17-cell cape region of the 64x32 layout is kept, at the source's scale rounded down to a power of two (at most 8x, less for long animations). Animated GIFs play with their own frame times. Capes go through the vanilla cape layer, so they swing like capes.
- **Your own cape** comes from your local Velo folder (`config/accounts/<uuid>/cosmetics-cape.json`, then the matching `.velocape` in `capes/`), as Velo itself does, since this mod never appears in Velo's online list.

Not supported for Velo: wings, auras and wear slots (Velo's 1.8.9 build doesn't draw them either, and the online list doesn't carry them), the Velo / Velo Plus badges (they're a client indicator, not a cosmetic), and a wardrobe tab (equipping in Velo is reported through its signed-in heartbeat, which would make this mod pose as Velo).

## Cosmetic colours

Clientmetics users can pick a colour for any cosmetic they're wearing (Lunar, Dawn or Essential),
and other Clientmetics users see it. In the wardrobe, open an equipped cosmetic's settings (the cog on
its card, or its row in the Equipped list) and click or drag on the hue bar, or pick a preset; the
refresh button puts its own colours back.

- **Shared through the Clientmetics colour server** (`server/` in this repository, separate from
  ClientTag's). It signs in with a Mojang session join, like ClientTag, and keeps only each player's
  colours by UUID. While you're on a server the mod sends your colours after a change and looks up
  the players around you every 30 seconds (sooner when new players appear). Only players whose mod is
  running are answered for.
- **Never unlocks anything.** A colour only changes how a cosmetic is drawn when Lunar, Dawn or
  Essential say that player is wearing it. Players without Clientmetics see the original colours.
- **How it's recoloured:** coloured textures have their hues turned so the main colour becomes the
  one picked (other colours turn with it; greys, blacks and whites stay). Mostly grey textures are
  tinted instead. White greys a coloured item out.
- Your colours are kept in `config/clientmetics/colours.json` (at most 64). Turn the whole thing off
  with `recolours` (**Cosmetic Colours** on the Clientmetics page). `-Dclientmetics.server=<url>`
  points the mod at another copy of the server.

## Running inside Lunar, Dawn, Essential or Velo

Each of those already draws its own cosmetics, so Clientmetics leaves that source to it and keeps the others:

- **Lunar Client** (its launcher sets `ichor.*` system properties; its classes are under `com.moonsworth.lunar`): Lunar cosmetics are off, Dawn and Essential still show.
- **Dawn Client** (Fabric mod `dawn`, or `feather`): Dawn cosmetics are off.
- **Essential** installed: Essential cosmetics are off.
- **Velo Client** (Fabric mod `velo-client`): Velo capes are off.

With none of them, all four run. A source left to its client also drops out of the wardrobe; use that client's own wardrobe instead. `-Dclientmetics.hostClient=lunar,dawn,essential,velo` (any of them) pretends they are present, for testing.

## ClientTag

If ClientTag is installed (found at runtime, not required):

- players ClientTag already knows aren't on Lunar are not subscribed;
- ClientTag users who only look like Lunar users are skipped, the same way ClientTag hides their Lunar icon;
- Mojang session joins go through ClientTag's queue, so the two mods don't trip Mojang's rate limit together.

## Wardrobe

`/lunarwardrobe` (or `/clientmetics wardrobe`, or the **Wardrobe → Open** button on the Clientmetics page, with OneConfig) opens a wardrobe of the cosmetics your Lunar account owns: a large live preview of your outfit (drag to turn), category tabs, search, and a grid of cards, each with a 3D preview of that cosmetic framed for its type (hats on the head, cloaks and backpacks from behind, and so on). Click a card to equip it (it replaces another of the same type) or to unequip it.

It's drawn with [PolyCompose](https://modrinth.com/mod/polycompose) (required; OneConfig isn't) and laid out like a page of OneConfig's menu: the same window, glow and shadow, the accounts in the sidebar, the categories as chips, and cosmetics as mod cards that light up when equipped. With OneConfig 1.9 or newer installed, the sidebar and back button also lead to OneConfig's menu and the Clientmetics settings, and it follows OneConfig's theme (Polyfrosted or Minecraft, dark or light), accent colour (including gradient), window opacities and glow, open and close animations and "pause game", including changes made while it's open. Without OneConfig it uses OneConfig's defaults (Polyfrosted Dark, blue accent). Previews are rendered into offscreen framebuffers and drawn inside the UI, the way PolyPlus draws its player preview.

Changes are saved to your Lunar account's default outfit with `CosmeticService.UpdateOutfit`, so Lunar users see them too. Only the outfit's cosmetics list is replaced; its name, skin and other settings are sent back unchanged. It needs the Lunar connection to be up (join a server, or a world with "Show My Own Cosmetics" on).

### Dawn

With `dawn` on, the wardrobe's header has a **Lunar / Dawn** switch, and `/dawnwardrobe` opens it on Dawn. The Dawn side lists the cosmetics your Dawn account owns (`GET /v2/cosmetics/owned`) and what's in each slot (`GET /v2/cosmetics/equipped`). Clicking a card puts it in its slot, replacing what was there, or empties the slot, and saves the whole slot map with `PUT /v2/cosmetics/equipped`, the same call the Dawn client makes, so Dawn users see it too. Slots this mod can't draw (shields, for example) are sent back unchanged.

- It signs in the same way as the Dawn connection (the game's Microsoft session), reusing that connection's token if it's up, so it doesn't need a server or world. Offline and dev accounts can't sign in.
- Emotes and trims aren't listed: they aren't equipped through slots.
- Cosmetics with only an AVIF texture show "No preview" but can still be equipped.
- Dawn items have no per-item settings, so the settings panel is Lunar-only.

### Essential

With `essential` on, an **Essential** tab appears once the catalogue has loaded, in both wardrobe
styles, and `/essentialwardrobe` (or `/clientmetics wardrobe essential`) opens it. It lists the
cosmetics Essential reports as owned and what's in each slot, with 3D previews. Clicking a card puts
it in its slot, replacing what was there, or empties the slot, and sends
`ClientCosmeticOutfitEquippedCosmeticsUpdatePacket` — the same call Essential's own wardrobe makes — so
the change is saved on your Essential account and other Essential users see it. It also updates your
own player immediately, without waiting for Essential to echo it back.

- Essential allows one cosmetic per slot, so equipping replaces that slot's cosmetic.
- Only cosmetics Essential reports as owned are listed. Clicking never buys, unlocks or moves anything.
- The change goes to the outfit Essential currently has selected.
- It needs the Essential connection up. Until Essential has sent your outfits, the tab says so.
- Slots this mod can't draw can still be equipped; per-item settings are Lunar-only.
- Cosmetics with no colour chosen use the cosmetic's default colour (the first in its `settings.json`).
- Animated textures (frames stacked down the image, sized by the model's `texture_width`/`texture_height`)
  play their frames; Essential ships no frame time, so 2 ticks per frame is an approximation.
- `animations.json` plays the `IDLE` trigger's animation (or the loop it hands over to). Emote and
  random-tick triggers, emissive textures and particles aren't supported; particle-only effects draw nothing.
- Essential's `arm_right`/`arm_left`/`leg_right`/`leg_left` bones follow the player's limbs.
- In first person, what of an Essential cosmetic hangs off an arm bone (a shirt's sleeve, gloves,
  bracelets) is drawn on the first-person arm too (the left arm shows when holding a map). Cosmetics
  attached whole to the head or body aren't, and the arm keeps first person's fixed pose.
- Essential capes all use the vanilla cape box and layout, so they're drawn by the vanilla cape layer
  (animated ones frame by frame): they swing like capes and cape mods such as WaveyCapes draw them.
- Essential's skin masks (`skin_mask.*.png`) say which skin pixels clothing hides. As Essential does,
  only those pixels of the outer layer are hidden: the player is drawn with a copy of their skin in
  which the covered outer-layer pixels are transparent, and the rest of each layer stays (a skirt
  hides the trouser pixels under it, not the whole trouser layer). The copy is what `getLocationSkin`
  returns, so vanilla, first-person arms, 3D Skin Layers' voxels and the Feminine Gender Mod all
  draw from it. Copies are shared by players with the same skin and outfit, and dropped 30 seconds
  after nobody is drawn with them.
- Essential's bone rules between worn cosmetics are applied: `COSMETIC_BONE_HIDING` (a maid outfit
  hides its body and arms under a hoodie) and `EXTERNAL_HIDDEN_BONE` (a hoodie hides a skirt's
  `body_outer`), so two clothes on the same surface don't fight.
- Essential cosmetics are drawn with a small depth bias: many clothes have a cube exactly the size of
  the body, on the skin's own surface, which would otherwise flicker against it.
- With the Feminine Gender Mod, an Essential top is drawn over its breasts, the way that mod fits
  armour: inside its own breast transforms (size, position, bounce physics), each breast gets a box
  for each of the top's body-sized layers, textured from that layer's chest, so the top follows the
  breasts instead of being cut through. A torso-covering cosmetic with no body-sized cube can't be
  fitted, so the breasts are hidden under it instead, as that mod does under a chestplate.
- Minecraft capes (Migrator and friends) aren't listed or drawn as Essential models. Essential makes
  them your real Minecraft cape, so the vanilla cape shows them. An equipped one stays listed so you
  can take it off. "Cape Disabled" hides the vanilla cape.

This mod never unlocks, buys or transfers cosmetics: the wardrobe only equips what your account
already owns.

It's a separate screen rather than part of PolyPlus's wardrobe. PolyPlus's wardrobe is a Compose UI whose cards and equip buttons are tied to PolyPlus's own items and server, so it can't be extended reliably.

## Config

With [OneConfig](https://polyfrost.org/projects/oneconfig) installed (optional), every setting below is on its **Clientmetics** page, also opened with `/clientmetics`. Changes apply within a second and are saved to `config.json`; changing the catalogue source or Lunar folder reloads the catalogue.

Without OneConfig, edit `config/clientmetics/config.json` (read at startup):

| Key | Default | |
| --- | --- | --- |
| `enabled` | `true` | Master switch: connect and draw at all (Lunar, Dawn, Essential and Velo) |
| `lunar` | `true` | Show Lunar Client cosmetics (off: Lunar is never contacted; Dawn and Essential still work) |
| `catalogueSource` | `"auto"` | `auto` (install if present, else download), `install`, or `download` |
| `lunarDirectory` | `""` | Lunar data folder; empty means `~/.lunarclient` |
| `downloadMissingAssets` | `true` | Fetch files the launcher hasn't cached |
| `dawn` | `true` | Also show Dawn Client cosmetics |
| `dawnDirectory` | `""` | Dawn data folder; empty means `%APPDATA%\.dawn` (or `~/.dawn`) |
| `essential` | `true` | Show Essential cosmetics and its wardrobe tab (unofficial; see above) |
| `velo` | `true` | Show Velo Client capes (reads Velo's public online list; no sign-in) |
| `showOwn` | `true` | Your own outfit in third person |
| `showCloaks`, `showHats`, `showBodywear`, `showGeckolib` | `true` | Per type |
| `animate` | `true` | GeckoLib animations |
| `maxPlayers` | `200` | Nearest players subscribed |
| `debug` | `false` | Log pushes and asset loads |

## Not supported yet

- Conditional outfits (by server, weather, movement): only `default_cosmetics` are used.
- The older non-GeckoLib dragon wings (209 items): Lunar draws them with a model built into the client, which isn't in the install's files.
- Emotes, auras' particle effects, morphs, cloth-cloak physics, shields held in hand.

## Updates

Once per launch (after the first-launch notice), the mod asks GitHub for the latest release of `ooTruffle/clientmetics`. If it's newer, you're offered it: as a OneConfig notification with OneConfig installed, otherwise on the title screen. **Update** downloads the release's jar into `config/clientmetics/update` (outside `mods`) and checks it against the SHA-256 GitHub lists for it; a release without one isn't offered. The new jar is installed when you close the game: a small helper process started from the new jar waits for the game to exit, puts it in `mods` and deletes the old jar. If the old jar can't be deleted, the new one is taken back out so two versions are never loaded, and the next exit tries again (see `config/clientmetics/update/update.log`).

Nothing is installed without clicking **Update**. Turn the check off with `checkForUpdates` (**Check for Updates** on the Clientmetics page). It's off in dev runs and when the mod isn't a plain jar in a folder.

## Build

```
./gradlew build
```

The jar is in `build/libs/` and nests TwelveMonkeys' WebP decoder (1.8.9 can't read WebP). PolyCompose (3.0.2), OneConfig (1.9.1), Kotlin and Compose are compiled against but not bundled. PolyCompose is a required dependency; Compose comes from it and Kotlin from Fabric Language Kotlin at runtime. OneConfig is optional, and the settings page only loads when it's installed.

Dev runs always load PolyCompose, and leave OneConfig out unless asked for:

```
./gradlew runClient -Pclientmetics.devOneConfig=true
```

`CLIENTMETICS_AUTOTEST=1` makes a dev run open a test world, fill the wardrobe with sample cosmetics cached on disk, save screenshots to `run/screenshots/` and quit. `CLIENTMETICS_AUTOTEST=dawn` does the same with Dawn outfits from a local Dawn install, ending with the Dawn wardrobe filled with demo data and, with OneConfig, a screenshot of OneConfig's own menu to compare against. With `CLIENTMETICS_AUTOTEST_THEME` (such as `Minecraft Light`) and/or `CLIENTMETICS_AUTOTEST_ACCENT` (hex RGB), the dawn run switches the wardrobe's theme and accent first and puts them back before quitting. With `-Pclientmetics.devCompat=true`, a dev run also loads the Feminine Gender Mod and 3D Skin Layers (jars in `libs/devcompat`, with fabric-language-kotlin) and the OSL modules they need; the Essential autotest then sets the dev player to female and checks a bare outfit, a hoodie and a skirt next to them (`clientmetics-essential-compat-*` screenshots).

`CLIENTMETICS_AUTOTEST=velo` checks Velo capes: your own cape from a generated local Velo folder (`run/velo-test`, a different colour on each face so a wrong crop or flip shows), then two Velo Store capes downloaded through Velo's API like any other player's. Each is screenshotted from behind, in front and walking (`clientmetics-velo-*`), checked to animate, freed and reloaded, and Velo's live online list is read once. Each check logs `[autotest] PASS` or `FAIL`.

`CLIENTMETICS_AUTOTEST=updater` (best with `-Pclientmetics.devOneConfig=true`) checks the updater against GitHub's real latest release: it pretends to be 1.0.0 installed as a fake jar in `run/updater-test/mods`, screenshots the offer (`clientmetics-updater-*`), downloads and verifies the release as if **Update** were clicked, and quits. Once the game has closed, `run/updater-test/mods` should hold only the release's jar; `run/config/clientmetics/update/update.log` says what the installer did.

`CLIENTMETICS_AUTOTEST=essential` checks the Essential side: default colours, chosen colours, Minecraft capes, "Cape Disabled" and the wardrobe's filtering. It also loads every wearable cosmetic in the catalogue (downloading what isn't cached) and checks that each animated texture splits into frames, then screenshots 12 mixed showcase outfits (`clientmetics-essential-showcase-*`). Each check logs `[autotest] PASS` or `[autotest] FAIL`, with a total at the end. It also screenshots three outfits (front and back) and the Essential wardrobe filled with demo data. It needs Essential's `catalogue.json` in `run/config/clientmetics/essential/` (copy it, and ideally `cache/essential/`, from any install that has connected once). Nothing is sent to Essential. None of these modes do anything outside the dev environment.

## Risks

Connecting to Lunar's servers from something that isn't Lunar Client is unsupported and may break Moonsworth's terms of service; the accounts at risk are your users'. Lunar's protocol changes over time, so expect breakage after Lunar updates. Turn `lunar` off to never contact Lunar.

The same goes for Dawn: its gateway needs a Dawn access token, which this mod gets by signing in with the player's Minecraft session outside the Dawn client. Dawn has no documented public API for this and its terms may not allow it. Turn `dawn` off to never contact Dawn.

Essential is riskier still: it talks to Essential's connection manager, which Essential neither documents nor permits, and which its source licence forbids reimplementing. It can break or be refused at any time. Turn `essential` off to never contact Essential.

Velo is lower risk (only public, unauthenticated endpoints are read), but it's still unofficial and undocumented, and the paths come from reading Velo 1.2.0's code. `-Dclientmetics.veloApi=<url>` points it elsewhere; turn `velo` off to never contact Velo.

Because of this, the first launch asks before anything is loaded or connects. The buttons unlock after a few seconds, and "Not now" keeps the mod idle until the next launch, which asks again. With ClientTag installed, its warning is shown first and this one follows. To skip the prompt (e.g. in a modpack), set `termsNoticeSeen` to `true` and `termsNoticeVersion` to the current notice version (`Consent.NOTICE_VERSION`, now `4`) in `config/clientmetics/config.json`. When the notice changes what's being agreed to, its version goes up and everyone who accepted an older one is asked again.
