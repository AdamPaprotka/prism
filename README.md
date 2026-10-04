# Prism Glass

A Fabric PvP / anarchy utility client for **Minecraft 26.1.2** with a liquid-glass ClickGUI, 100+ modules and
anticheat bypasses tested against real servers running **GrimAC** and **Updated-NoCheatPlus**.

> Use it on anarchy servers or servers where clients like this are allowed. Breaking a server's rules can get you banned.

## Highlights

- **Liquid glass GUI:** a real refracting glass shader, HUD editor (drag every element), Blocky pixel-art style, profiles and shareable config codes.
- **StreamProof:** the GUI, HUD and ESP are hidden from OBS, Discord and other screen capture (fullscreen too); only you see them.
- **AntiCheat presets** (Grim, NCP, Vulcan, 2b2t, Vanilla): rotations, move fix, packet order and reach tuned per anticheat.
- **Tested bypasses:** each one checked on a local server, counting alerts and setbacks.

| | Grim 2.3.74 | Updated-NCP 3.17.1 |
|---|---|---|
| NoSlow (Grim mode, ~2.6x eating speed) | ✅ | ❌ |
| Timer (Balance mode / NCP mode) | ✅ | ✅ |
| Scaffold + Tower (Legit) | ✅ | ✅ |
| AirPlace (Grim mode) | ✅ | ✅ |
| NoJumpDelay | ✅ | ✅ |
| KillAura, Criticals (Legit) | - | ✅ |

- **Finders:** seed-based StructureFinder and ElytraFinder (end ships + the exact elytra), Xray with seed ore simulation.
- **PearlPredict + AutoPearl:** shows where every pearl lands; AutoPearl follows enemies who pearl away (about 1 block off on average).
- **ElytraBot:** autopilot to coordinates with safe landings.

See [CHANGELOG.md](CHANGELOG.md) for everything, or open it in game (ClickGUI → Config → Changelog).

## Install

1. Install [Fabric Loader](https://fabricmc.net/) **0.19.3+** for Minecraft **26.1.2** and [Fabric API](https://modrinth.com/mod/fabric-api).
2. Drop `prism-glass-<version>+mc26.1.2.jar` into your `mods` folder.
3. In game, press **Right Shift** to open the ClickGUI.

## Commands

Prefix `.` (change it with `.prefix`). `.help` lists everything.

| Command | What it does |
|---|---|
| `.toggle <module>` / `.bind <module> <key>` | Toggle / bind a module |
| `.set <module> <setting> <value>` | Change a setting |
| `.config <save\|load\|list\|export\|import>` | Profiles and config codes |
| `.share <module...>` | Copy modules' settings as a code |
| `.friend <add\|del\|list\|clear> [name]` | Friends (never targeted) |
| `.elytrabot <x> <z>` | Elytra autopilot |
| `.hud` | HUD editor |
| `.pearltest [distance]` | Test PearlPredict / AutoPearl in singleplayer |
| `.about` / `.changelog` | Version, build info and changelog |
| `.panic` | Turn every module off |

## Building

Needs **JDK 25**.

```bash
./gradlew build
```

The jar ends up in `build/libs/`. `./gradlew runClient` starts a dev client.

## License

[MIT](LICENSE)
