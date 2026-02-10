# Copilot Instructions — TgStorage

## Project Overview

TgStorage is a **serverless Android app** that uses a **Telegram bot + private channel** as free cloud storage. There is **no custom backend** — the phone is the server, Room/SQLite is the source of truth, and Telegram is append-only remote storage. See `roadmap.md` for the full phase-by-phase plan.

## Tech Stack (Locked — Do Not Deviate)

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + **Material 3 only** |
| Navigation | Navigation Compose |
| Local DB | Room (SQLite) |
| Background sync | WorkManager |
| Networking | OkHttp + Kotlin Serialization |
| Encryption | AES-256-GCM via Android Keystore |
| Telegram | Official Bot API (`https://core.telegram.org/bots/api`) |
| Build | R8 + resource shrinking enabled |

**Do not** introduce Expo, React Native, Firebase, any cloud DB, or heavy third-party SDKs.

## Architecture Rules

- **Offline-first**: every screen must work without internet. Room DB is always the source of truth; Telegram is the sync target.
- **Parallel development**: build UI screens and their corresponding backend/sync logic together in the same phase — never defer one for the other.
- **Chunked file transfer**: files over the Bot API limit (~50 MB upload / 20 MB download via `getFile`) must be split into chunks. Each chunk maps to its own Telegram `message_id`. Store chunk metadata (index, hash, message_id) in the `chunks` Room table.
- **Clean architecture**: use repository pattern → data source (Room + Telegram API). ViewModels expose UI state via `StateFlow`.
- **Every screen must have 4 states**: loading, populated, empty, and error with retry action.

## Screen Map (Phase → Screens)

| Phase | Screens Built |
|---|---|
| 1 | Splash / Welcome, App Shell with NavigationBar, Theme setup |
| 2 | Onboarding (3 steps: how-it-works, bot token, channel verify) |
| 3 | Home — File Browser (grid/list, search, filter), File Detail / Preview |
| 4 | Upload / Import, Download / Export, Transfer Queue |
| 5 | Sync Dashboard, Backup & Restore |
| 6 | Settings, Security / Encryption |
| 7 | Storage Stats, About / Help, full UI polish pass |

## Key Data Model

```
files        → local metadata, size, SHA-256 hash, encryption flag
chunks       → per-chunk: index, file_id FK, telegram message_id, checksum
sync_state   → pending_upload | uploaded | failed | deleted
metadata     → bot token (encrypted), chat_id, settings
```

## Telegram Bot API Usage

- Reference only `https://core.telegram.org/bots/api`.
- Upload: `sendDocument` to the private channel.
- Download: `getFile` → fetch via `https://api.telegram.org/file/bot<token>/<file_path>`.
- Store `chat_id`, `message_id`, `file_id`, `file_unique_id` per upload.
- Bot token must be encrypted at rest using Android Keystore.
- DB backup: encrypt Room snapshot → upload as a document → store its `message_id` for restore.

## UI / UX Conventions

- **Material 3 Compose components only** — no legacy View XML, no custom design system.
- Match the feel of Google's own apps (Files, Keep, Drive): clean, spacious, friendly.
- Every screen needs: loading state, empty state, error state with retry, offline indicator.
- Upload/download progress must use `LinearProgressIndicator` or `CircularProgressIndicator`.
- Use `TopAppBar`, `NavigationBar`, `FloatingActionButton`, `Card`, `ListItem` from M3.
- Dynamic color (Material You) support where possible.

## Code Quality

- Production-quality Kotlin: named arguments, sealed classes for UI state, `Result`/`Either` for errors.
- Room migrations must be explicit — never use `fallbackToDestructiveMigration` in release.
- WorkManager constraints: require network for upload jobs, allow retry with exponential backoff.
- Keep APK size minimal: no unused deps, no analytics SDKs, shrink resources.

## Development Workflow

- Follow `roadmap.md` phases sequentially — do not skip ahead.
- Within each phase, build frontend + backend in parallel.
- Test each phase's success criteria before moving on.

## Security Checklist

- Encrypt files before upload (AES-256-GCM).
- Encrypt DB backup before upload.
- Store master key in Android Keystore.
- Never log or expose bot token in plaintext.
- Wipe temp/cache files after use.
