# TgStorage

**Free cloud storage powered by Telegram** — a serverless Android app that uses a Telegram bot + private channel as unlimited free cloud storage.

No backend. No subscription. Your phone is the server. Telegram is the drive.

## Features

- **Unlimited free storage** — Upload files to your private Telegram channel
- **End-to-end encrypted** — AES-256-GCM encryption before anything leaves your device
- **Offline-first** — Works without internet, syncs when connected
- **Chunked transfers** — Large files split into chunks with integrity verification
- **Auto sync** — Background upload/download via WorkManager
- **Material You** — Dynamic color, dark/light themes, clean M3 design
- **In-app updates** — Automatic update checker from GitHub releases
- **Tiny APK** — Under 3 MB release size

## How It Works

1. Create a Telegram bot via [@BotFather](https://t.me/BotFather)
2. Create a private channel and add your bot as admin
3. Enter the bot token in TgStorage — it auto-detects your channel
4. Upload files — they're encrypted and stored in your channel
5. Download anytime — decrypt and restore on any device

## Tech Stack

| Layer           | Technology                       |
| --------------- | -------------------------------- |
| Language        | Kotlin                           |
| UI              | Jetpack Compose + Material 3     |
| Navigation      | Navigation Compose               |
| Local DB        | Room (SQLite)                    |
| Background sync | WorkManager                      |
| Networking      | OkHttp + Kotlin Serialization    |
| Encryption      | AES-256-GCM via Android Keystore |
| Telegram        | Official Bot API                 |

## Building

```bash
# Clone
git clone https://github.com/CypherNinjaa/tgstorage.git
cd tgstorage

# Debug build
./gradlew assembleDebug

# Release build (requires keystore setup)
./gradlew assembleRelease
```

### Release Signing

Create `keystore.properties` in the project root:

```properties
storeFile=../release-keystore.jks
storePassword=your_password
keyAlias=tgstorage
keyPassword=your_password
```

## Download

Get the latest APK from [GitHub Releases](https://github.com/CypherNinjaa/tgstorage/releases/latest).

The app includes a built-in update checker — go to **About → Check for Updates**.

## Privacy

- **No telemetry** — zero analytics, no tracking
- **No backend** — your phone talks directly to Telegram
- **Encrypted** — files are encrypted before upload
- **Open source** — audit the code yourself

## License

[MIT](LICENSE)
