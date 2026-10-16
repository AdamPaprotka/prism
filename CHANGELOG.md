# Prism Glass changelog

## 1.6.0 - 2026-10-16

### StaffWatcher (Client)
- Tells you when staff are watching you: a totem pop animation with the staff member's head instead of the totem (plus totem particles, a ping and a red chat line).
- Staff = names on your staff list (`.staff add/del/list/clear`, tab completes online names), a staff word in their tab-list rank or team prefix (`[Admin]`, `[Sr.Mod]`, `[Helper]`... editable RankWords), anyone seen in spectator, and anyone caught vanishing.
- Watching = a staff player keeps their crosshair on you (LookAngle, LookTime, Range, ThroughWalls), or a player right next to you switches to spectator (servers stop sending spectators to you, so that's how spectating someone starts). Cooldown per person, NearAlert when staff come close, OnlineAlert lists staff online when you join and when they join. The HUD shows how many are online and "watched" while someone is looking.
- AntiVanish: someone leaves the tab list with no leave message = vanished (only trusted once the server has shown a real leave message; always reported when it happens right next to you); someone appears with no join message = unvanished; and the Probe asks the server to tab-complete `/msg ` every few seconds - names it knows that aren't in your tab list are online but vanished. Paper filters vanilla command completion for hidden players, so try a plugin command in ProbeCommand on those servers.
- With StreamProof on the head pop is skipped (it would show on stream); the alert goes to the hidden toasts instead.
- Tested with fake players fed through the real tab-list packet handler: rank/list detection, looking, cooldown, spectator switch, leave with and without a message, unvanish, probe (12/12).

### BookBot (World)
- Writes every empty book and quill you carry: one letter repeated, a word repeated, your own text, or random letters. Pages (1-100), characters per page (1-1024), optional signing with a title and #1, #2... numbering, delay, count. Books in the main inventory are moved to a free hotbar slot first. Stays inside the vanilla limits, which the server enforces anyway.

### Dupes (Anarchy)
- ItemFrameDupe: puts the held item into the closest item frame and punches it out again, over and over (optional rotations first, refills your hand). Only duplicates on servers running a frame-dupe plugin; vanilla/Paper just give the item back.
- ShulkerDupe: open a placed shulker box, the module mines it and shift-clicks everything out in the same tick. Only works on servers that still accept clicks on a broken box's menu; vanilla 26.1.2 rejects them (tested: no duplication).
- SpamDupe + `.spamdupe [seconds] | stop`: for servers that have a /dupe command. Dupes what you're holding until your inventory is full (works out the N for you), throws everything straight up except one item, and repeats. Runs for the item pickup delay by default (2 s in vanilla) and always finishes the cycle it's on, so you end up holding one. Command template (`dupe {n}`), whether N counts stacks or items, MaxN, cycle delay, throws per tick, and a safety stop when too many items are lying around you. Tested with `give` standing in for /dupe: 3 cycles in 2 s, ~6,650 items thrown, other items untouched.
- XCarry (Player): keeps items in the 2x2 crafting grid as 4 extra slots. Turning it off puts them back in your inventory; logging out with items in the grid drops them where you logged out.

### Staircase (World)
- Builds a staircase in the direction you face. Up climbs one block per step into the air: it sneaks to the edge of the step you're on to place the support, puts the step on top, jumps up and repeats. Down goes one block lower per step and digs it into the ground/hills. Both clear 3 blocks of headroom (digging through whatever is in the way with your best tool) and walk along as they go. Length (0 = endless), block type (any / cobblestone / obsidian / netherrack), Dig, Walk, LockYaw. Down in open air needs AllowAirPlace in AntiCheat (there's nothing to click against), it tells you instead of walking off.
- Tested: 8 steps up through a stone ceiling, 8 steps down into a stone hill.

### StorageHover (Render)
- Look at any chest, barrel, shulker box, hopper, furnace, ender chest... and a glass card shows: type (double chests too), capacity (slots and max items) and its full size. Open it once and it also shows used/free slots, item count, the top items, the item grid and the size of what's in it, from then on without opening.
- Sizes are the real data size of the items (the NBT a chest saves, same counting as Meteor's byte-size tooltip): `Size: 1.26K Bits, 157 Bytes, 0.153 KiloBytes, 0.00015 MegaBytes, 0.000000146 GigaBytes`. Bits and Bytes are shortened with K/M/B/T, 1 KiloByte = 1024 Bytes.
- Servers never send a chest's items until it's opened, so remembered contents are from the last time you (this session) opened it.

### ClickGUI
- PlayerHat (Config menu): the "China hat" cosmetic, a see-through shaded cone floating over your head (F5), optionally on other players too. Takes Prism's accent colour by default (follows your theme, rainbow accents run round the brim); HatPrismColor off = your own HatColor. HatOpacity, HatSize, HatSpin. Only your own client draws it.
- AutoWidth (on by default): every panel is as wide as its longest module name plus FreeSpace, so long names never touch the + marker. PanelWidth is now the minimum width. Panels that would overlap in your saved layout are pushed apart when the GUI opens.

## 1.5.0 - 2026-10-11

### Command tab completion
- Type your prefix and part of a command (`.tog`, `.set crys`) and the same suggestion list as vanilla's /commands pops up: Tab, arrows or a click to pick. It knows every command and its arguments: module names, setting names, setting values (modes, true/false, keys), online players for `.friend add`, your friends for `.friend del`, profiles, waypoints.
- The command's usage and description show above the chat box while you type; an unknown command shows in red.

### PacketMine (Player)
- Click a block once and it's mined in the background: keep fighting, look away, hold anything. Your best tool is swapped in at the end (Silent: only the server sees it), so the block breaks at full pickaxe speed while you hold a sword or a crystal.
- Instant mode (vanilla / Paper / anarchy servers): the server finishes the block by itself. Strict mode (Grim / NCP): the stop packet goes out when the block is really done, with the tool swapped in a tick before.
- Click more blocks to queue them (Queue). Rebreak mines a block again by itself when someone places it back (an enemy fixing their surround). The box grows and turns from red to green as it breaks.
- Tested: obsidian with an Efficiency V netherite pickaxe in another slot breaks in 48 ticks (vanilla 43) in both modes while holding a stick.

### AntiSurround (Combat)
- Puts an end crystal into your enemy's surround slot so they can't place the block back: Broken mode only fills slots whose block was just mined out (AutoCity / PacketMine combo), Gaps fills every open slot. Only on obsidian/bedrock floors, skips crystals that would hurt you more than MaxSelfDamage. Mobs option for practising on hostile mobs. Uses the AntiCheat place range (not the shorter attack range).

### BetterTooltips (Render)
- Extra tooltip lines: exact durability (coloured), food hunger/saturation, shulker contents summary, how many of the item you're carrying, anvil uses, bees in a hive, map number, item id.
- Ender chest preview: open your ender chest once and hovering an ender chest item shows what's inside, like ShulkerPeek.

### About screen
- The update button has its own full-width row (it used to squeeze in next to the other three and its text ran off it), highlighted when there's an update and with a progress bar while downloading.

## 1.4.4 - 2026-10-09

### NowPlaying
- The song and lyrics screens grow out of the part of the HUD box you clicked and shrink back into it when you close them (Esc twice skips the animation). Switching between Song and Lyrics resizes smoothly.
- The Spotify link reads normally instead of %20s.

## 1.4.3 - 2026-10-09

### NowPlaying
- Always 4 rows of lyrics: the line being sung on the second row with " <-" after it, what's next below, what was just sung above. Long lines wrap onto the next row and push the rest down. Wider box (up to 340), slower and smoother scrolling (same speed at any FPS).
- LyricsTranslate (Off / Below / Replace) + LyricsLang: translations under each line or instead of it. Translated once per song and saved, so replays never ask the translator again.
- With chat open, click the cover for the song screen (big cover, artists, album, time, open in Spotify / copy link) or the lyrics for the whole sheet top to bottom (timestamps, translations, current line highlighted and followed).
- Slowed / sped up / nightcore versions get the original song's lyrics with every timestamp stretched to match (your track's length / the original's), so they line up instead of running ahead or behind.
- Spotify ads show as "Ad break" (no lyrics or cover lookups for them).
- When the song is past its last lyric line, the lyrics fade out and the song name fades in.
- No more music note placeholders.

## 1.4.2 - 2026-10-08

### Updater
- Prism checks GitHub for a newer release when you join a world and tells you with a toast. The About screen (click the Prism logo) and the Changelog have an Update button: it downloads the jar for your Minecraft version next to the current one, and when you close Minecraft the old jar is swapped for the new one (Windows keeps the running jar locked, and two Prism jars would stop Fabric from starting). Restart and you're updated.

## 1.4.1 - 2026-10-08

### KillAura: knockback displacement
- KBDisplace + KBAngle: bend where your sprint hits knock them. A hit's knockback is a 0.4 push straight away from your position plus a 0.5+ sprint/Knockback push that follows the hit's yaw, so aiming the hit's yaw off by KBAngle bends the flight.
- On Grim the yaw has to stay inside their hitbox (a few degrees at normal range): tested 0 alerts, same hit rate. KBFull uses the whole angle on vanilla / no-AC servers.
- KBLines: guide on the target. White line from inside them pointing at you (always), pink line at KBAngle off it, yellow line where a hit will actually send them. Everything is relative to you, so it stays right as you move.

### ElytraBot doesn't fly into walls
- Looks ~1.5 s ahead every airborne tick (look and velocity direction, rays across the hitbox). When blocked it scans a fan of headings (up to 150 degrees each way, level to steep climb), takes the clearest one closest to the target and turns faster while dodging. Works while climbing, cruising and landing (it only used to check straight ahead while cruising).
- Rockets only fire into open air (including the takeoff rocket, which used to boost into hillsides).
- Tested: flew around a 61-wide wall up to build height placed 45 blocks after takeoff, 0 collisions, full health, landed 1 m from the target.

## 1.4.0 - 2026-10-08

### New modules
- AutoMace (Combat): every hit while falling swaps to your best mace for that hit (smash damage grows with the fall). WindJump: with an enemy close, jumps and throws a wind charge straight down to launch you; AutoHit smashes the enemy below once you've fallen MinFall.
- BreachSwap (Combat): every hit (yours or KillAura's) uses your Breach mace for that one hit (armour piercing) and swaps back next tick.
- SpearKill (Combat): stabs the moment an enemy is between the spear's 2 and 4.5 block reach at full charge, aims at them, and holds the charged lunge while you sprint at them. AutoSwap to a spear in your hotbar.
- Waypoints (Render): `.wp add <name> [x y z]`, `.wp del`, `.wp list`, `.wp clear`. Beam + distance label, overworld/nether conversion, automatic Death waypoint. Saved per server/world.
- ShulkerPeek (Render): hover a shulker box in any inventory or chest to see its 27 slots.
- SnapAnims (Render): no tweening between ticks, so players jump from tick to tick. Blocks mode also snaps them to the block grid (Grid size, SnapY), YawStep snaps turning to 45/90 degree steps. Players / Self / Mobs / Others. Visual only.

### HUD
- NowPlaying: Spotify box at the top with the album cover and the song, or synced lyrics scrolling up (next lines white, current gray, past dark gray). Reads the Spotify desktop app through the Windows media session, so no login. Lyrics from Musixmatch when it is Spotify (one guest token, saved in prism/musixmatch.properties), with lrclib.net as the fallback; the cover is Spotify's own (from the media session), or an iTunes search for other players. Only artist/title/album are sent. Settings: NowPlaying, Lyrics, AnyPlayer. The box resizes to fit the text.
- Watermark shows the release channel: Prism 1.4.0 beta.
- Fix: the HUD could switch itself off when you died, respawned or left a server (one frame with no world). The HUD and AntiCheat are never auto-disabled after an error any more.

### Freecam
- ForceRender (on by default): turns off chunk occlusion culling while flying, so areas behind walls or behind you no longer vanish.

### Combat fixes (tested on Grim 2.3.74: 0 alerts)
- BreachSwap only swaps once the mace itself is charged (MinCharge). A mace charges in about 33 ticks against a sword's 12.5, so swapping at sword pace gave weak mace hits (7 damage against the sword's 19 in testing).
- AutoMace: after the wind charge launch it steers you over the target, so the smash lands in reach (it used to fall 3.5 blocks away). Tested: 68 damage in 8 s against 9 before, 0 alerts.
- ElytraFly Bounce: pitch 75 tested best on Grim (186 blocks in 10 s, 0 setbacks); it's the default.

### Keybinds tab (ClickGUI top bar)
- Keys for things that aren't modules: Inspect (default I: CS:GO-style inspect animation of your held item), Quick Pearl (throw a pearl without switching to it), Mark Waypoint, Copy Coords, HUD Editor, Panic. Left click to set a key, right click to clear.

### PearlPredict
- Alerts: toast + ping when an enemy's pearl is about to land near you (AlertRange), with who and when.

## 1.3.0 - 2026-10-04

### New
- ElytraFly: Bounce mode (highway travel): hold W with an elytra on; it sprint-jumps, re-opens the elytra right after each jump and holds BouncePitch (silent, camera free) so speed builds up. Tune BouncePitch for your server. Not tested against anticheats yet.
- HighwayBuilder (World): builds a highway in the compass direction you face (8 directions, diagonals included). Digs a Width x Height tunnel with your best tool, paves missing floor (Obsidian / Netherrack / any block), blocks off lava and water, adds guardrails, and walks along once the next stretch is done. One action per tick at vanilla speed: tested on Grim, 0 flags (27-block wall, floor hole and water on the way).
- ElytraFly: new Grim mode (default). Your camera steers left/right; the module flies the pitch: Space climbs, Shift dives, neither holds your height, and rockets fire when you get slow. It only ever changes your look (a silent rotation, so your camera stays free) and uses real rockets: tested on Grim, 0 flags.

### Fixes
- Elytra flight and firework boosts use the silent rotation in your own physics too, so any rotating module (KillAura, AutoPearl...) no longer desyncs from Grim while you glide.
- Using an item (pearl, rocket...) keeps the silent rotation still for that tick (Grim BadPacketsJ).
- Placing and digging only aim at blocks the server's real 4.5 reach can ray to (Grim RotationPlace near max range).

### Project
- GitHub Actions: every push builds the jar (Actions > Artifacts); pushing a tag like v1.3.0 publishes a Release with the jar attached.

## 1.2.0 - 2026-10-04

### NoCheatPlus (tested on Updated-NCP 3.17.1 b279, the 2026 build)
- AntiCheat NCP preset updated for the new NCP. It now predicts movement from your keys and sent yaw like Grim, so the preset uses the silent move fix and rotates inside the movement packet. Re-pick the NCP preset to apply it.
- Timer: new NCP mode. Steady speed kept under NCP's packet limits (about 1.05x, never over 15 packets in half a second). 0 setbacks.
- Step: new NCP mode (sends the jump arc for 1-block steps). The new NCP still catches it, so it's only for older NCP versions.
- Verified clean on NCP: KillAura, Criticals Legit, NoJumpDelay, AirPlace Grim mode, Timer Balance, Scaffold tower, silent rotations while walking or sneaking.
- Not possible on the new NCP: NoSlow (NCP force-releases your item), Speed Strafe (kicks), Criticals packet modes, ReverseStep, FastClimb.

### AutoPearl (in PearlPredict)
- When an enemy pearls away, it works out where their pearl lands and throws yours at the angle that lands you closest to them: simulates every throw angle with vanilla pearl physics (your own speed included), refines the best arcs, then throws Grim-safe (silent rotation, swap and swap back next tick). Tested: about 1 block off on average out to 45 blocks.
- Settings: FollowRange, MinDistance, MaxError, Prefer (Balanced / Fastest / Closest).
- `.pearltest [distance]` in singleplayer throws a test "enemy" pearl next to you to try it.
- Fix: item-use rotations are sent continuous with movement rotations (no 360 degree flips).

### ClickGUI
- Click the Prism logo for the About screen: version with a release / beta / indev badge, build number and time, Minecraft / Fabric / Java versions, GPU, memory, modules, mods, AC preset, profile, StreamProof and session. "Copy info" puts it on your clipboard for bug reports (nothing personal in it). Also `.about`.

### Scaffold
- Looks straight back at the block like a real bridger the whole time (NCP Scaffold Angle/Rotate checks).
- Keeps holding the block slot while you bridge and swaps back after a short pause, at the start of a tick (NCP ToolSwitch, Grim Post).
- New Sprint setting (off by default): sprinting while placing under you flags NCP.

## 1.1.0 - 2026-10-04

### Grim bypasses (tested on GrimAC 2.3.74: 0 alerts, 0 setbacks)
- NoSlow: new Grim mode (default). Unslowed every other tick, which Grim's NoSlow check never catches. About 2.6x vanilla speed while eating, blocking or drawing a bow.
- Timer: new Balance mode (default). Bursts at your Speed with the time Grim lets you bank (about 120 ms + ping), checked before every single tick, and refills while you stand still. A steady timer above 1.0 can't pass Grim.
- Scaffold: Grim-safe bridging. Aims at the next block early and really sneaks for the moment you're at an edge (fake SafeWalk got flagged and dropped you).
- Scaffold: TowerMode Legit (default) towers with real jumps. Fast mode is the old boost and is Grim-unsafe.
- AirPlace: new Grim mode (default). Places at the spot you look at by clicking a block next to it. Green box = works, red = touches nothing. Clicking pure air always flags on Grim.
- NoJumpDelay: verified Grim-safe (+48% speed jumping under ceilings).
- Not possible on Grim: Step, FastClimb, ReverseStep (Grim simulates them exactly).

### Fixes
- Silent rotations (Scaffold, KillAura, Surround...) no longer flag Simulation or rubber-band you while sneaking or eating. 26.1 normalizes the movement input and the move fix didn't.
- Looking behind you with a silent rotation no longer sends a 360 degree yaw snap (Grim AimModulo360).

### HUD
- HUD editor: drag every HUD element with the mouse. Open it with the HUD button in the ClickGUI or `.hud`. Snaps to edges and the centre, right click resets, and positions scale with your screen.
- ListFormat: Brackets shows the module list as `[AC mode] [Module] [info]`.

### ClickGUI
- Blocky: pixel-art version of the whole GUI (glass included); text stays sharp. Setting BlockSize.
- Changelog viewer (Config menu or `.changelog`), plus a toast once after an update.

## 1.0.0 - 2026-10-03

### Minecraft 26.1.2
- Full port to 26.1.2 (unobfuscated names, new GUI renderer); the 1.21.4 version stays maintained.

### StreamProof
- Hides the GUI, HUD and ESP/tracers from OBS, Discord and other screen capture, fullscreen included. You still see everything. Optional software cursor while the ClickGUI is open.

### New modules
- StructureFinder: seed-based structure prediction (about 99% accurate) plus loaded-chunk detection.
- ElytraFinder: finds end ships and the exact elytra item frame, and tells you if it's looted.
- ElytraBot: elytra autopilot to coordinates (`.elytrabot x z`); climbs, cruises, rockets and lands with no damage.
- PacketFly: Vanilla and Bounds modes.
- PearlPredict: shows where ender pearls will land (0.002 block error).
- AutoTranslate: auto-detect from/to, incoming and outgoing chat, StreamSafe mode.
- CrystalAura v2: exact damage maths, target prediction, breakable-only, anti-weakness.

### ClickGUI and config
- Config | Profiles bar next to the search.
- Share configs as codes: whole profile (Export/Import) or single modules (`.share`).
- List settings (Xray/Search/Nuker blocks...) open a list editor on right click.
- Binds work while the GUI is open; the search clears after 5 s or Esc.
- Walk while the ClickGUI is open.
- HUD always draws on top of everything.

### Fixes
- Xray lag spikes (incremental scan).
- Flight modules ignore WASD while Freecam is on.
- Crash with some modpacks (MixinExtras) on startup.

## 0.9.0 - 2026-10-02
- First build (1.21.4): liquid glass ClickGUI and HUD, 90+ modules, AntiCheat presets researched from Grim and NoCheatPlus source.
