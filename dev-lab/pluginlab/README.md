# PluginScanner lab

A real Paper server that hides its commands, for `./gradlew runClient -Ppluginstest -Pmp=localhost:25610`
(the network half of `dev/PluginsNetTest`). Nothing here ships in the client jar.

## What is in it

| jar | plugin | what it does |
| --- | --- | --- |
| `lab-hiderlab.jar` | HiderLab | `hidermode 0-3` (console / RCON): 0 open, 1 hidden, 2 strict, 3 namespaces hidden. Cuts the command tree with `PlayerCommandSendEvent`, empties tab-complete with `AsyncTabCompleteEvent`, disguises `/plugins` and friends as unknown commands with `PlayerCommandPreprocessEvent`, then re-sends the tree to everyone. |
| `lab-luckperms.jar`, `lab-worldedit.jar`, `lab-coreprotect.jar` | stubs named like the real plugins | register `luckperms` (`lp`, `perms`), `worldedit` (`we`), `coreprotect` (`co`, `core`) and answer them with their name and a usage line. Stubs: they exist so the scanner has something to find, nothing more. |

The four HiderLab modes:

- **0 open**: nothing is touched.
- **1 hidden**: tree cut to `help msg tell w me trigger teammsg tm list`; tab-complete of a bare `/` is empty; `/plugins`, `/pl`,
  `/bukkit:plugins`, `/bukkit:pl` answer "Unknown command". The usual setup with the usual gap: `/version <tab>` and `/help <tab>`
  still list plugin names, and `/l` `/c` ... still complete.
- **2 strict**: as 1, but every tab-complete outside `msg tell w me` is empty and `/version`, `/ver`, `/about` are disguised too. The
  plugins' own commands still run, so only the error probe can still tell they exist.
- **3 namespaces hidden**: only the `plugin:command` copies are removed from the tree (what `send-namespaced: false` does).

## Running it

1. Get a Paper 26.1.2 server directory (jar + `libraries/` unpacked by one start), `eula=true`, and in `server.properties`:
   `online-mode=false`, `server-port=25610`, `enable-rcon=true`, `rcon.port=25611`, `rcon.password=prismtest`.
2. Build the jars into its `plugins/` (JDK 25; the Paper 26.1 API is Java 25 bytecode):

   ```
   JAVA_HOME=<jdk 25> python dev-lab/pluginlab/build_lab.py <paper dir>
   ```
3. Start the server (stop it before rebuilding: never overwrite a jar a running server holds), then in the client project:

   ```
   ./gradlew runClient -Ppluginstest -Pmp=localhost:25610
   ```

   The window is hidden and muted like every other harness. `PRISM_RCON=port:password` overrides the RCON defaults
   (25611:prismtest). The test walks through five scenarios (open, hidden, strict, namespaces hidden, hidden with the sweep off),
   leaves the lab in mode 0 and ends with `ALL PASS` or `SOME FAILED`.

The local half (`./gradlew runClient -Ppluginstest` without `-Pmp`) needs no server: it dresses the integrated server up as a Bukkit
one.
