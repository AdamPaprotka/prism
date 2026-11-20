# Writing a Prism addon

A Prism addon is a normal Fabric mod (Minecraft 26.3) that also declares a `prismglass` entrypoint.

## fabric.mod.json

```json
{
  "schemaVersion": 1,
  "id": "myaddon",
  "version": "1.0.0",
  "name": "My Addon",
  "environment": "client",
  "entrypoints": { "prismglass": ["com.example.myaddon.MyAddon"] },
  "depends": { "prismglass": "*", "minecraft": "~26.3" }
}
```

## build.gradle

Compile against the Prism jar (it isn't on a Maven repo):

```groovy
dependencies {
    modCompileOnly files("libs/prism-glass-1.6.3+mc26.3.jar")
}
```

## The addon

```java
package com.example.myaddon;

import dev.prismglass.api.PrismAddon;
import dev.prismglass.api.PrismApi;
import dev.prismglass.util.ChatUtil;
import java.util.List;

public class MyAddon implements PrismAddon {
    @Override
    public void onInitialize(PrismApi api) {
        api.registerModule(new AutoHello());
        api.registerCommand("hello", "hello <name>", "Says hello.",
            args -> ChatUtil.good("Hello " + args[0]),
            args -> args.length == 0 ? List.of("world") : List.of());
    }
}
```

## A module

```java
package com.example.myaddon;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.NumberSetting;

public class AutoHello extends Module {
    public final NumberSetting every = num("Every", 60, 1, 600, 1, "Seconds between messages.");
    private int ticks;

    public AutoHello() { super("AutoHello", "Says hello now and then.", Category.WORLD); }

    @Override
    public void onTick() {
        if (++ticks >= every.get() * 20) {
            ticks = 0;
            mc.getConnection().sendChat("hello");
        }
    }
}
```

Settings are fields made with `bool`, `num`, `mode`, `color`, `key`, `text`, `list`, `choices`. Hooks (only called
while the module is on and a world is loaded): `onEnable`, `onDisable`, `onTick`, `onPostTick`, `onRender2D`,
`onRender3D`, `onPacketSend`, `onPacketReceive` (cancel with `event.cancel()`), `onMove`, `onAttack`, `onWorldJoin`.

Rules:
- Module and command names must not clash with Prism's (the call throws).
- `api.folder()` is your own data folder (`.minecraft/prism/addons/<addon>`).
- `api.macros()`, `api.friends()`, `api.modules()` reach Prism's managers.
- Prism's internal classes can change between versions; only `dev.prismglass.api`, `Module`, `Category` and the
  settings are meant to stay stable.
