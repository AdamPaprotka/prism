# Prism Glass: anticheat notes

These bypasses come from reading the source of **GrimAC** (2.x, `common/.../checks/impl`) and
**Updated-NoCheatPlus** (`checks/fight`). Pick a profile with `.set AntiCheat Preset <Vanilla|NCP|Grim|2b2t|Custom>`;
every module reads its limits from it. Nothing is guaranteed: anticheats update, and servers tune their configs.

## How the core works

| Mechanism | Where | What it's built against |
|---|---|---|
| Modules run at the very start of `MinecraftClient.tick`, before input and before the movement packet | `Prism.onTickStart` | Grim **PacketOrderO** flags actions between the movement packet and `CLIENT_TICK_END` |
| Silent rotations, turn-speed capped, Gaussian jitter, deltas snapped to your mouse-sensitivity GCD | `RotationManager` | Grim **AimProcessor / AimModulo360**, NCP Angle |
| Never send an unchanged look packet | `RotationManager.sendLook` | Grim **AimDuplicateLook** |
| Attack/place only when the rotation the server *already has* ray-traces the target | `RotationManager.isFacing` | Grim **Reach / RotationPlace**: they test the current or previous tick's rotation |
| Reach measured **along that look ray** to where it enters the hitbox (not to the closest point), limit 2.9 | `RotationManager.rayDistance`, `CombatUtil.attack` | Grim **Reach** ray-casts exactly like this; 0.1 slack covers its interpolated target box |
| Velocity push options forced off under Grim/2b2t presets | `AntiCheat.enforceGrimSafeVelocity` | Grim **Simulation**: it predicts entity/block pushing, so ignoring a push in melee is a prediction miss |
| MoveFix: re-pick WASD relative to the server yaw, and run velocity with the server yaw | `MovementHooks`, `EntityMixin` | Grim movement prediction uses the yaw from your movement packet |
| Use-item packet carries the silent rotation | `PacketGuard.fixUseRotation` | Grim **BadPacketsJ** |
| Mirrors Grim's PacketOrderProcessor on outgoing packets | `PacketGuard` | **PacketOrderE** (slot change after attack/place in the same tick), **MultiPlace**, **MultiInteractA**, **PacketOrderI/J**, **BadPacketsA** |
| Slot swap-backs are deferred to the next tick | `InvUtil.restore` | **PacketOrderE** |
| CrystalAura places before it breaks | `CrystalAura` | **PacketOrderJ**: a place after an attack in the same tick flags |
| Place range measured to the closest point of the clicked block; only visible faces | `BlockUtil` | **FarPlace**, **PositionPlace**, **AirLiquidPlace** |
| Attack range 2.9 on Grim, crystals included | `AntiCheat.AttackRange` | Grim Reach: 3.0 + 0.0005 threshold, and `END_CRYSTAL` is checked |
| NCP preset: 3.3 range, 160 ms minimum hit spacing | `AntiCheat` | NCP Reach shrinks to about 3.2 when abused; NCP Angle flags an average hit gap under 150 ms |

## Reach / Hitboxes (Grim + NCP)

* **Grim:** its Reach check tests the union of every position the target can occupy while your client
  interpolates toward the newest server position (`ReachInterpolationData#getPossibleLocationCombined`), plus
  a 0.0005 threshold and +0.03 when your last movement had no position. `HitboxUtil.serverBox` rebuilds the
  part of that union we know for sure (drawn box + box at `serverX/Y/Z`), always inside Grim's own, so hits on
  it can't flag. Moving targets give roughly 0.2-0.5 extra reach; still targets give vanilla reach.
  Used by KillAura/Trigger (AntiCheat `InterpReach`), Reach `Grim`, and Hitboxes `Grim` (crosshair re-pick).
* **NCP:** `ReachBudget` mirrors NCP's dynamic reach (4.02, -0.14/4.02 per hit beyond 3.22, floor 3.22,
  recovers on closer hits). Reach `NCP` and the NCP preset only reach as far as that budget allows.

## Grim knockback: GrimBudget

From Grim's source and default config: every ignored knockback is one AntiKB (or Explosion) violation;
the rubberband setback only starts at **10** violations; violations expire after **300 s**; the default
punishment for this group is an alert at **5** and a log entry, with no kick or ban. Velocity `GrimBudget`
fully cancels up to `Budget` (default 4) hits per `Window` (300 s), so no setback and, on a stock config,
no staff alert. Then it falls back to legit JumpReset. Servers with custom punishments (a ban at a low
Knockback VL) can still act on it, so lower `Budget` if you suspect that.

## Vulcan preset

Vulcan is closed source (paid, obfuscated), so unlike Grim/NCP its checks could not be read. The `Vulcan`
preset is built from its publicly known behaviour: heavy aim heuristics (so slower, jittered, GCD-aligned
turns: 55 deg/tick, jitter 1.2), roughly 3.1 reach (we use 3.0), multi-target and packet-order checks
(kept strict), and a velocity check focused on vertical knockback (try Velocity `VerticalOnly`). Treat it as a
starting point and report flags the same way.

## Safe / unsafe by module (Grim)

**Safe by design:** KillAura, Trigger, CrystalAura, Surround, SelfTrap, AutoTrap, HoleFiller, AutoTotem,
Offhand, AutoArmor, Criticals (Legit), Velocity (JumpReset; GrimBudget stays under the setback/alert limits), Sprint (Legit), Parkour, AutoJump, Sneak,
AutoWalk, SafeWalk, Scaffold (Extend 0, no Tower), Speed (GrimCollide), NoFall (Bucket), SpeedMine (Packet),
AutoEat, AutoTool, MiddleClick, every Render module.

**Flags on Grim** (tagged `[Grim-unsafe]` in their descriptions): Criticals Packet/NCP, Velocity
Cancel/Percent (AntiKB threshold is 0.001), Speed Strafe/Vanilla, Flight, ElytraFly, Step >0.6, Spider, Jesus,
HighJump, LongJump, Glide, AirJump, ReverseStep, FastClimb, NoSlow (prediction based, no mode passes), Timer >1,
Blink (Timer on release), Burrow (fake positions), NoFall Packet, AntiHunger, Reach >0, Hitboxes, AirPlace,
AntiLevitation, NoJumpDelay.

## When you report a flag, send me

1. The check name from the server's alert or `/grim verbose` (e.g. `PacketOrderE`, `RotationPlace`, `Reach`).
2. Which module(s) were on, and the AntiCheat preset.
3. The server, if it isn't stock Grim (2b2t runs its own anticheat).

The check name maps straight to the code above, so most fixes are a one-spot change.
