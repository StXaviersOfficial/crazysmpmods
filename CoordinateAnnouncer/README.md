# CoordinateAnnouncer

A Paper 1.21.11 plugin for the CrazySMP server that periodically broadcasts
player coordinates in chat with a 10-second countdown.

## Requirements

- **Minecraft Java Edition**: 1.21.11 ("Mounts of Mayhem")
- **Paper**: 26.2 build line (or later 1.21.11-compatible)
- **Java**: 25+ (required by Paper for 1.21.11)
- **Permissions**: operator (OP) only by default

## Features

- ⏱️ Configurable delay (default 60 minutes) with unit toggle (Seconds / Minutes / Hours / Days)
- 🌍 Announces ALL online players OR a custom-selected list
- 💾 Last-known position cache — offline players can still be announced with cached coords
- ⚠️ 10-second countdown in chat: warnings at 10s, 5s, 4s, 3s, 2s, 1s before announcement
- 📋 Beautiful chest GUI for all settings (no commands needed!)
- 🛡️ NPC filtering (Citizens, Carpet mod fake players, etc. skipped)
- 💿 All settings persist across server restarts (atomic file writes — no corruption on crash)
- 🔒 OP-only — no permission nodes needed

## Commands

| Command | Description |
|---------|-------------|
| `/ca` or `/coordinateannouncer` | Show help |
| `/ca toggle` | Enable / disable announcements |
| `/ca gui` | Open the chest customization menu |
| `/ca delay <value> <unit>` | Set delay (e.g. `/ca delay 60 minutes`) |
| `/ca player add <name>` | Add player to custom list |
| `/ca player remove <name>` | Remove player from custom list |
| `/ca player list` | List custom players |
| `/ca player clear` | Clear custom list |
| `/ca mode all` | Announce every online player |
| `/ca mode custom` | Announce only custom-listed players |
| `/ca offline show` | Show offline players with last-known coords |
| `/ca offline skip` | Skip offline players silently |
| `/ca info` | Show current settings |
| `/ca reload` | Reload config from disk |
| `/ca help` | Show help |

## Permissions

| Permission | Default | Description |
|------------|---------|-------------|
| `coordinateannouncer.admin` | OP | Use all Coordinate Announcer commands |

## Configuration

All settings are stored in `plugins/CoordinateAnnouncer/config.yml` and can
be edited in-game via `/ca gui` or manually with `/ca reload` afterwards.

Default `config.yml`:
```yaml
enabled: false
delay:
  value: 60
  unit: MINUTES
mode: ALL
offline-handling: SHOW
custom-players: []
position-cache-throttle-ms: 5000
filter-npcs: true
countdown-global: true
```

Last-known positions are stored in `plugins/CoordinateAnnouncer/data.yml`.

## Announcement Format

```
════════════════════════════════════════
              COORDINATE ANNOUNCER
════════════════════════════════════════
QuackPlayzYT → 20 28 -483 (Overworld)
Steve → -100 70 250 (Nether)
[OFFLINE] Alex → Last known: 5 64 100 (Overworld)
════════════════════════════════════════
```

## Countdown Format (in chat)

```
⚠ WARNING: 10 seconds before the coordinates get announced!
⚠ 5 seconds before the coordinates get announced
⚠ 4
⚠ 3
⚠ 2
⚠ 1
```

## Building

```bash
cd CoordinateAnnouncer
./gradlew build
# Output: build/libs/CoordinateAnnouncer-1.0.0.jar
```

Drop the .jar into your Paper server's `plugins/` folder and restart.

## Bug-prevention checklist

This plugin was designed with the following edge cases handled:

- ✅ Coords snapshot at T-0 (not at countdown start — no drift if player moves)
- ✅ Minimum delay enforced at 15s (countdown needs 11s + 4s buffer)
- ✅ Empty custom list + CUSTOM mode → cancel with warning, no spam
- ✅ NPC filtering (skip fake players, Citizens NPCs, etc.)
- ✅ Offline player handling: SHOW vs SKIP per config
- ✅ Last-known coords cached on PlayerQuitEvent + throttled PlayerMoveEvent
- ✅ Atomic config writes (temp file + rename — no corruption on crash)
- ✅ Plugin reload cancels running tasks cleanly before restart
- ✅ Server restart: countdown state NOT persisted (safety)
- ✅ Toggle state persists; if enabled at shutdown, stays enabled at next start
- ✅ Tab completion for all subcommands
- ✅ OP-only via plugin.yml permission default

## License

MIT — see [LICENSE](../LICENSE) (in parent repo).
