# Prism Glass changelog

## 1.6.3 - 2026-10-23

### 20 more modules (the rest of the list)
- **Movement:** ClickTP (right-click a block with an empty hand to teleport onto it: hops of up to ~10 blocks along a clear line, refused when something's in the way) [Grim-unsafe]; ElytraBoost (real firework from the hotbar/offhand on BoostKey or below MinSpeed, swapped back the next tick; Fake mode pushes like a rocket client-side [Grim-unsafe]); EntityControl (steer horses/donkeys/camels without a saddle and pigs/striders without the stick, MaxJump makes every horse jump full) [unsafe: vanilla servers don't accept it]; Follow (walks after a player with real key presses; `.follow <name> | off`, or picks who you look at / the closest); Slippy (every block gets ice friction) [Grim-unsafe].
- **Player:** GhostHand (right-click opens the first container on your crosshair line, through walls) [Grim-unsafe]; NoInteract (right-clicks on crafting tables, anvils, beds, villagers... do nothing; with a block in hand it places against them like sneaking instead); NoRotate (server teleports and rotation packets can't turn your camera; the teleport is still confirmed with the server's rotation); InventoryTweaks (SortKey, middle mouse by default, sorts the inventory under the mouse with one swap a tick; AntiDrop stops Q throwing totems/elytras/gapples/crystals; AutoDrop throws listed junk); AutoCraft (crafts the listed items through the recipe book while a crafting table or your inventory is open; CraftAll, Drop).
- **MiddleClick:** new Air action (Pearl, Rocket, XP, WindCharge, Gapple, None) and ElytraRocket (a rocket while gliding). XP keeps throwing while the button is held, Gapple eats while it is held.
- **Combat:** AutoBedCrafter (under Refill beds with wool + planks: opens the nearest crafting table, or places one, crafts beds to Target and closes it); SpearPhase (lunge dashes on a key: swaps in your Lunge spear, stabs the way you look or walk and swaps back; the server's lunge push moves you, about 3 blocks per stab at Lunge III).
- **World:** VeinMiner (break one block of an ore vein / log and it mines the rest of the connected blocks, one at a time with vanilla timing; stone and deepslate ores count as one vein); LiquidFiller (fills lava/water source blocks around you, nearest first); AutoSmelter (furnaces, smokers and blast furnaces: tops up input from your list or anything it accepts, tops up fuel, takes results); AutoBrewer (brewing stands: water bottles in, each ingredient of the chosen potion in turn, Strong/Long upgrade, Splash/Lingering, blaze powder fuel, finished potions out).
- **Aliases:** names other clients use now find Prism's module in commands and the ClickGUI search: AutoCrystal → CrystalAura, CityBreaker → AutoCity, BlockEsp → Search, NoBob → NoRender, Strafe → Speed, BedBomb → BedAura, WallHack → Xray, EXPThrower → AutoXP, MazeKill → TunnelKill, MiddleClickExtra → MiddleClick.
- Not added: BuildHeight, MountBypass and PotionSaver patch things 26.1 no longer has; Blobman, EnchantBreaker and AutoDoubler aren't well defined anywhere.

### SpamDupe: ToChest and cycles
- New **Output** setting: ThrowUp (as before) or **ToChest**, which opens the nearest chest / barrel / shulker in reach and shift-clicks the copies in, moving to the next one when it fills up. You still end every cycle holding one.
- `.spamdupe [cycles]` now runs that many cycles instead of seconds (no number = the Cycles setting, 5). The module list shows done/total.

### NoFall works with Flight
- Landing after flying (Vanilla/Creative Flight) no longer hurts: the client resets its fall distance while flying but the server keeps counting every block you came down, so NoFall now tracks the fall from the position packets it sends and marks the landing packet safe. Tested: fly up 20, fly down, land = full health (without NoFall: 2 HP left).

### Flight: Grim mode
- New Flight mode **Grim**: flying that Grim cannot flag. Hovering in open air is rubber-banded by Grim within about 13 ticks (cancelling gravity costs 0.078 blocks of prediction offset every tick), so this mode flies on blocks instead: Scaffold keeps a block under your feet wherever you go. WASD walks on air, Space climbs (a tower when you stand still, steps when you move), standing still hovers, Shift (DropOnShift) stops placing and lets go of the edge so you drop (land in water or use NoFall Bucket).
- Needs blocks in the hotbar and something to place against when you start. It sets Scaffold up the Grim-verified way (Legit tower, SafeWalk, Extend 0) for as long as Flight is on and puts your own Scaffold settings back afterwards.
- Tested against a real Grim 2.3.74 (default config): 17 blocks of air-walking, a 18 x 12 diagonal climb, a 12 block tower, hovering and a Shift drop, all with 0 alerts and 0 setbacks. Flight Vanilla/Creative on the same server: 56 and 19 setbacks in 100 ticks (they stay [unsafe]). New -Pgrimtest cases: flygrim-walk, flygrim-climb, flygrim-hover, flygrim-mix, scaffold-sprint, scaffold-climb, fly-vanilla, fly-creative. Details in BYPASSES.md.

### PingSpike (Movement)
- Lag switch: `.pingspike` holds EVERY packet to and from the server, `.pingspike` again (or toggling the module) releases them all in their original order. `.pingspike <ticks>` holds for exactly that many ticks (1-1200) and releases by itself; `.pingspike <ticks>` while holding restarts the countdown. Alias `.ps`. Chat shows when it starts and ends with how many packets went out / came in and how long it lasted; the module list shows live held counts and ticks left.
- Outbound packets are held after PacketGuard and RotationManager have seen them (their state keeps following real time) and the flush bypasses them; inbound packets are replayed through the Prism hooks and then handled normally on release.
- The server kicks you ~15 s after a missed keep-alive, so a spike is auto-released after MaxSeconds (default 10, 0 = no cap). Disconnecting, or the world/connection changing while holding, drops the held packets instead of flushing them into another session.
- Tested: -Ppingspiketest (integrated server: movement hidden from the server while held, server teleport hidden from the client while held, exact tick count, MaxSeconds cap, disconnect while holding).

### PluginScanner: finds plugins even when the commands are hidden
- Until now PluginScanner only read the server's command list and the tab-completion of "/", so a server that strips the tree, blocks /plugins or hides namespaced commands looked exactly like a server with no plugins. It now combines many signals into one result: every plugin gets a confidence (low / medium / high / confirmed) with the evidence behind it, and the command surface gets a verdict, OPEN / FILTERED / HIDDEN, with the reasons ("the tree has no namespaced commands", "tab-complete of / came back empty", "/plugins is answered like an unknown command", "3 of 4 plugins only showed up by name" ...). "No plugins" and "plugins hidden" are no longer the same message.
- Seen without sending anything (always on, also for what arrived before you switched the module on): the command tree (namespaced commands; plain command names; sub-command shapes that still recognise a renamed command; Bukkit-style nodes that give Paper/Spigot away even behind a faked brand), plugin-message channel ids (voice chat, WorldEdit CUI, proxies, Fabric/Forge servers), ping/transaction packets (vanilla and Paper send none, so a stream means a transaction anticheat; Grim's scheme is recognised by ids that step down by one), resource pack hosts (Oraxen/ItemsAdder/Nexo hints), the server brand and the server-list version (Paper, Velocity, BungeeCord ...; "server says 1.21.4 but you joined with 26.1.2" means version translation like ViaVersion), and how often the tree was re-sent.
- Asked quietly (tab-complete is not a chat message and not in the server's normal log; at most one per ProbeDelay = 8 ticks, MaxTabProbes = 48 per scan, far under Spigot's tab-spam kick): "/", "/version " and "/help " (Bukkit completes both with plugin names, which plugins that block /plugins often forget), "/bukkit:" and "/minecraft:" when the tree has no namespaced commands, the alphabet "/a" ... "/z" when "/" came back blocked or short (TabSweep Off / Auto / Always), "/plugin:" for plugins that were only named, and /ver /about /bukkit:version when /version is dead. Paper does not answer a tab-complete with an empty result, so silence counts as empty, never as "blocked".
- Opt-in, real commands (the server can log them): ProbeCommands runs /bukkit:plugins and reads the list. ErrorProbe runs a made-up command, /plugins and up to ErrorProbeCount (5) harmless bare commands (/lp, /ess, /we, /grim, /wg ...) and compares each answer with the made-up one: "unknown command" means absent, a refusal or a banner means the plugin exists even if every list hides it. `.plugins deep` = both, plus the alphabet sweep. Only answers that talk about the command count (other chat in the window is ignored and never stored or logged).
- How sure: a namespaced command in the tree or tab-complete 90, the server's own name list (/version completion, /bukkit:plugins) 99, a refused/answered probe 85, a plugin channel 85 (exact) or 70 (prefix), the server brand 85, a distinctive command name 55 (+14 for each more), a renamed command recognised by its sub-commands 62, Grim's ping scheme 70 (other negative ids 55, any other ping stream 45), a help topic 70 (50 if the database does not know the name), a resource pack host 40, weak command names 12 each. Confidence is the combination of the strongest fact of each kind, so the tree and tab-complete showing the same namespace never add up to "confirmed"; 97+ = confirmed, 80+ high, 50+ medium.
- Chat output: Server (software, proxy), Commands: OPEN / FILTERED / HIDDEN with the reasons, Plugins (n) coloured by confidence (hover = the evidence, click = copy), an Anticheat line, commands no signature explains, unknown channels. `.plugins why` shows the verdict reasons, the ping profile and the database size; `.plugins why <name>` every piece of evidence for one plugin; `.plugins deep`, `.plugins probe`, `.plugins last` as before. The HUD panel shows the verdict and a colour per plugin. New settings: VersionProbe, HelpProbe, NamespaceProbe, TabSweep, ErrorProbe, ErrorProbeCount, ProbeDelay, MaxTabProbes, MinConfidence, ShowCommands.
- Signature database: `assets/prismglass/plugin_signatures.json` in the jar, about 100 entries: the anticheats (Grim, NoCheatPlus, Vulcan, Matrix, Intave, Polar, Verus, Spartan, AAC, Karhu, Themis, Negativity, Kauri ...), LuckPerms, Essentials/CMI, WorldEdit/FAWE, WorldGuard, CoreProtect, GriefPrevention, ViaVersion/ViaBackwards/ViaRewind/ProtocolSupport, Geyser/Floodgate, login and skin plugins, ProtocolLib, command-hiding plugins, plus the server software and proxies. To add your own, create `prism/plugin_signatures.json` (same format, comments allowed; an entry with an existing id replaces the bundled one, `"remove": ["id"]` drops one) and run `.plugins reload`; the bundled file documents every field.
- What did not make it, and why: announcing channels with minecraft:register (26.1 keeps only the id of an unknown custom payload and cannot send its bytes, so channels are seen, not asked for; Bukkit only sends plugin messages to clients that registered them anyway); timing "unknown" against "no permission" answers (a ping's jitter is bigger than the difference); keep-alive patterns (Paper sends one a second, vanilla one per 15 seconds, so no usable fingerprint); join/kick/chat-format fingerprints (they need reading chat text, which Prism does not); setback patterns (only visible once an anticheat has already flagged you).
- Tested: `-Ppluginstest`. Local half (97 checks): the integrated server dressed as a Bukkit one (namespaced fake plugins in the tree and tab-only, fake /version and /help completions, /bukkit:plugins, bare-command answers, real ping and channel packets), plus the scoring and the verdict on invented servers (plain Paper, everything cut away, vanilla, spoofed brand, renamed commands), the server-list/brand/resource-pack inputs, and the database (lint, user file, merge). Network half (71 checks): a real Paper 26.1.2 with stub plugins that hide commands (`dev-lab/pluginlab`): open, hidden, strict, namespaces hidden, and hidden with the sweep off; the connection stays up in every scenario.

## 1.6.2 - 2026-10-19

### Proxies + Tor
- Proxies (Accounts screen -> Proxies, or `.proxy`): SOCKS5, SOCKS4 or HTTP proxies for the server connection, like Meteor's. Pick one and the next server you join (and server list pings) goes through it, so the server sees the proxy's IP. "Direct" turns it off. Add one as `name host:port [type] [user pass]`; saved in prism/proxies.json.
- Tor built in: the "Tor" entry starts tor.exe for you (Tor Expert Bundle in prism/tor or ~/tor; a Tor Browser already running on 9050 is used as is), shows the bootstrap state, "New identity" gets a fresh circuit and usually a new exit IP. Expect 250-500 ms ping: fine for chat, scouting and scans, not for fighting.
- "Check IP" / `.proxy ip` shows the IP the internet sees through the proxy and whether it differs from your real one.
- `.proxy account <account> <proxy>` ties a proxy to a cracked account: logging in as it switches the proxy too.
- Only the game connection is proxied; the Mojang session check of a premium account still goes out directly, and the server hostname is resolved by your DNS before the proxy sees it.

### Accounts + fake players
- Accounts (ClickGUI top bar, or `.account`): cracked logins like a launcher's offline mode. Type any name, Login, and the next server you join sees that name (offline-mode servers only; online-mode ones refuse it, as always). "Original" goes back to the account the game started with; Random makes a name up. Saved in prism/accounts.json. `.account add|del|login|original|random|list [name]` does the same from chat.
- `.joinplayer [name] [true|false]` logs a real second player into the server you are on (`true` = from a new IP: the bot gets its own Tor circuit, started for you if needed, and tells you which IP it came in from). With ViaFabricPlus the bot talks the same server version you do: its own connection, cracked account with that name (offline-mode servers; online-mode ones refuse it). It stands at its spawn, answers keep-alives, teleports and respawns, and everyone sees it. `.sayplayer <name|all> <message>` chats as it (a leading / runs a command), `.leaveplayer <name|all>` drops it. Bots go through the active proxy, so with Tor on they have a different IP than you. Aliases `.bot` `.sp` `.lp`.
- `.switchplayer <bot> [drop]` makes you that bot. Your current connection is handed to a bot in place: the socket stays open, the bot answers keep-alives from then on, and the server never sees your old account leave (no join checks run on it again). Then the target bot disconnects, your session switches to its cracked account and the client logs in as it. `drop` disconnects your old account instead of keeping it as a bot. For when one account got a captcha, a watch or a mute and the other didn't.
- `.fakeplayer [name]` / `.fakeleave` is the old client-side dummy: your skin and gear, only on your screen, target practice.

### PluginScanner (World)
- Lists the server's plugins. Bukkit registers every plugin command a second time as "plugin:command"; those namespaced copies sit in the command tree the server sends on join and in the tab-completions of "/", and the namespace is the plugin's name.
- Scans on its own a few seconds after joining (AutoScanOnJoin), reads the join tree (passive) and asks the server to tab-complete "/" (ActiveScan, for servers that trim the join tree). ProbeCommands (off by default) also runs /bukkit:plugins and reads the answer, which gives the real names; that is a real command the server can log.
- Chat output: server brand (ShowBrand) and "Plugins (n): ..." sorted by name; hover a name for the commands it registers, click the list to copy it. Hud shows the list as a glass panel you can drag in the HUD editor (HudLines caps it).
- IgnoreList hides the server's own namespaces (minecraft, bukkit, spigot, paper, purpur, pufferfish, folia, velocity, bungeecord, waterfall, fabric, forge, neoforge); it is a free-text list, so add whatever a server spams.
- `.plugins` scans now (`.plugins probe` also runs /bukkit:plugins once, `.plugins last` reprints), aliases `.pl` and `.pluginscan`; both tab-complete.
- Tested: -Ppluginstest (the integrated server gets namespaced fake plugin commands and a /bukkit:plugins; real tree, tab-complete and chat packets).

### ClickGUI: type slider values
- Right-click any slider to type an exact number: Enter applies, Esc cancels, clicking anywhere else or closing the GUI applies too. Comma or dot both work. The value is kept inside the slider's range but not rounded to its step, so 4.37 on a 0.5-step slider stays 4.37. The first key you type replaces the old value.
- Reset moved to middle-click (right-click used to reset).
- Slider values always show with a dot (12.5), whatever your system language.

### PacketMine: instant rebreak
- Rebreak is now a mode: Instant (default), Normal, Off. The server never forgets where your last mining START was or when it was sent, and a lone STOP packet for that spot counts every tick since then. So when a block you mined gets placed back (an enemy fixing their surround), PacketMine silently swaps your best tool in and breaks it again with one STOP, as long as the time since the original START covers 70% of the break time with that tool.
- Mining anything else in between resets that on the server: then it falls back to a normal rebreak automatically.
- Tested on the vanilla 26.1.2 server: Efficiency V diamond pick on obsidian, normal mine 47 ticks, instant rebreak 3 ticks with the held slot unchanged (-Prebreaktest 5/5). Not tested against Paper patches or Grim's FastBreak yet.

### SusChunks (World)
- Finds chunks where amethyst geodes have grown out, which only happens where a player kept the area loaded for hours (base, AFK spot, stash). Budding amethyst only grows on random ticks, and those only run within simulation distance of a player: a fresh geode has about 9% of its open faces holding a full cluster, one left loaded for hours ends up near 100%.
- Per chunk it counts every open face of budding amethyst and what grows on it; Growth (default 60%) and MinFaces decide the flag. Box over the geode part, "% grown" label, chat message with coordinates, optional tracers, count in the HUD.
- Area: the player stood within SimDistance chunks of every geode they grew, so the overlap of those squares around a group of grown chunks is drawn as where they were. Set SimDistance to the server's simulation distance.
- Method Palette for anti-xray servers that scramble single blocks (Auto picks it on Folia servers): a chunk with full clusters but no smaller bud counts as grown.
- Tested: -Psustest 6/6 (grown vs fresh geode, area, palette mode).

### ClickGUI PopOut (hologram) - BETA
- Beta: it works and is tested (-Pholotest), but it is new and touches the GUI renderer; if it misbehaves, turn PopOut off and tell me.
- New ClickGui setting PopOut: the whole ClickGUI floats in the world as a tilted, slightly curved glass panel, placed in front of where you look when you open it. Your HUD, hotbar and chat stay on the screen as normal.
- Your mouse turns your head, and an arrow cursor sits where your crosshair hits the panel. Click, right click, scroll and drag sliders or panels by looking at them. Clicks that miss the panel do nothing.
- The panel stays fixed in the world where it was placed: turning your head sweeps the cursor across it, and you can walk around it. HoloFace (on) smoothly turns it to keep facing you as you move (HoloTurn = how fast it eases round, 30 = instant), without moving it; hold Shift (or your sneak key) to stop it turning while you aim; it follows your position, not where you look, so the cursor still sweeps across it. A gentle idle sway (HoloSway) moves it a few centimetres.
- Works with StreamProof: the panel and its cursor are drawn into the hidden layer with the world's camera, so you see it lined up with the world and capture sees nothing.
- If the panel can't be drawn (another mod skipping the world render hook), the ClickGUI stays the normal flat one with a free mouse instead of locking your mouse.
- Settings: HoloTilt (how far it leans back), HoloDepth (perspective strength), HoloSway (gentle floating drift), HoloFace (turn to face you), HoloTurn (turn speed), HoloSize (1 = 60% of your view's height, at any FOV), HoloCurve, Cursor colour, CursorSize. Background blur and dim are skipped while it's popped out, so the world stays sharp.
- While popped out the menu's liquid glass is drawn as clean see-through glass: refracting a blurred screenshot doesn't line up with a tilted panel and made it look smeared (worst with StreamProof, where the blur got baked into the hidden layer). The HUD keeps its liquid glass.
- Closing the GUI hands the mouse back to the game, or to whatever screen you open next.
- Tested: -Pholotest 23/23 (clicks, right click, toggles, slider drag by head movement, steering, panel fixed while turning and walking, off-panel clicks, closing, flat fallback) and the same with StreamProof on 24/24 (capture frame has no panel, the hidden layer has it), no GL errors.

## 1.6.1 - 2026-10-16

### StaffWatcher: AntiVanish upgrade
- Hidden from TAB but still connected = vanished. Many vanish plugins only unlist you; Prism now compares the listed tab and spots it instantly, no leave-message guessing needed. `.staff list` marks them [hidden].
- Left the tab list while their body is still standing in the world = vanished.
- Back in the tab after vanishing = "visible again".
- Shard/server switches (4+ tab changes at once, or a new world) no longer look like everyone vanishing; tab entries younger than 1.5 s are ignored.
- Probe now uses `minecraft:msg` (the vanilla command), which skips plugin /msg commands that hide vanished players. When it returns lots of off-tab names (the command lists the whole network), only known staff names count.
- DonutStaff (on): DonutSMP's staff accounts count as your staff list. RankMarkers: star-marked ranks (Donut style) count as staff.
- Invisible staff near you trigger an alert.
- GhostSense: a chest/barrel/shulker opening, a door, a block placed or broken, or a player sound where no visible player or mob could have done it (not you, not redstone, not an explosion, not crops or piston farms). The first one is noted in chat; a second close by within 15 s is the alert, named when exactly one hidden staff member is known.
- Tested: -Pstafftest 22/22.

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
