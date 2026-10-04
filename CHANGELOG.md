# Prism Glass changelog

## 1.3.0 - 2026-10-04

### New
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
