# VexalBridge

Production-ready Minecraft ↔ HTTP bot bridge for **Folia 1.21.x** (also runs unchanged on Paper/Purpur 1.21+).

The plugin only makes **outbound** HTTP requests. It never opens a port.

## Build

```bash
./gradlew build          # → build/libs/VexalBridge-1.0.0.jar
```

On Windows:

```bat
gradlew.bat build
```

Requires **Java 21**.

## Install

1. Copy `build/libs/VexalBridge-1.0.0.jar` into the server `plugins/` folder.
2. Start the server once to generate `plugins/VexalBridge/config.yml`.
3. Set `bot.base-url` and `bot.token` (replace `CHANGE-ME`).
4. Run `/vexalbridge reload` or restart.

Missing/placeholder config disables HTTP features and logs a clear warning — the server still starts.

## Wire protocol

| Direction | Method | Path |
|-----------|--------|------|
| Events out | `POST` | `{base-url}/minecraft/events` |
| Commands in | `GET` | `{base-url}/minecraft/commands?limit=10` |
| Results out | `POST` | `{base-url}/minecraft/commands/result` |

Headers on every request:

- `X-Bridge-Token: <token>`
- `Content-Type: application/json` (POSTs)
- `User-Agent: VexalBridge/1.0 (Folia)`

## Commands

| Command | Permission | Description |
|---------|------------|-------------|
| `/vexalbridge status` | `vexalbridge.admin` | Config, poll, queue, last id, scheduler mode |
| `/vexalbridge reload` | `vexalbridge.admin` | Reload `config.yml` and restart poller |
| `/vexalbridge test` | `vexalbridge.admin` | Send one `custom` test event |

Players with `vexalbridge.silent` are not forwarded.

## Future-proofing

- No NMS / CraftBukkit
- No version-string parsing
- Adventure Components only (no `ChatColor` / `§`)
- Folia-safe scheduler shim (`Scheduler.java`)
- Individually guarded event registration
- Additive config keys with in-code defaults
- Command ids persisted to `state.json` (no re-run after restart)
