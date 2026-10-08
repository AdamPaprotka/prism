# Prism Glass changelog

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
