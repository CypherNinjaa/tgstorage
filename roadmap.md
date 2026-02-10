# 📱 Telegram‑Backed Free Storage Android App — Roadmap (Bot + Channel)

> **Offline‑First · 100% Free · No Custom Backend · Minimal App Size · Material 3 UI**

---

## 🎯 Product Vision

Build a **serverless Android application** where:

- The **Android phone is the brain / control plane**
- **Telegram bot + private channel** provide free cloud storage and sync
- All metadata is stored in a **local database (Room / SQLite)**
- Telegram `message_id` values act as **remote references**
- No VPS, no Firebase, no AWS, no custom backend

Primary use cases:

- Personal cloud storage
- Encrypted file vault
- Notes / documents sync
- Offline‑first productivity tool

---

## 🧠 Core Principles (Non‑Negotiable)

- Offline‑first by default
- No custom server infrastructure
- Telegram access via **official Bot API** per https://core.telegram.org/
- Local database is the **source of truth**
- Telegram is append‑only remote storage
- Privacy & encryption first
- User fully owns their data
- **Material 3 UI only** (Google official library)
- Minimal app size and dependency footprint

---

# 🗺️ Project Roadmap (Zero → Production) — Parallel Frontend + Backend

> Each phase builds **UI screens and backend logic together**. No phase is backend‑only or UI‑only.

---

## PHASE 0 — Product Definition & Feasibility (≈ 1 week)

### 0.1 Goals

- Lock the problem statement, constraints, and success metrics
- Validate the Telegram Bot API capabilities and limits

### 0.2 Tasks

- Define MVP scope and non‑goals
- Confirm Telegram Bot API methods for file upload/download (`sendDocument`, `getFile`)
- Confirm file size limits (~50 MB upload, ~20 MB download via `getFile`) and rate limits
- Decide single user vs multi‑user scope (initially single user)
- Decide supported Android versions (min API 26 / Android 8)
- Map out all app screens and navigation flow

### 0.3 Deliverables

- Requirement brief
- Telegram API capability checklist
- MVP definition
- Screen map (see below)

### 0.4 Full Screen Map (All Phases)

| #   | Screen                    | Phase Built | Purpose                                                       |
| --- | ------------------------- | ----------- | ------------------------------------------------------------- |
| 1   | **Splash / Welcome**      | 1           | First launch branding, brief tagline                          |
| 2   | **Onboarding (3 steps)**  | 2           | Explain concept, enter bot token, verify channel              |
| 3   | **Home — File Browser**   | 3           | Grid/list of files, folder-like categories, search bar        |
| 4   | **File Detail / Preview** | 3           | Name, size, type, sync status, thumbnail/preview, actions     |
| 5   | **Upload / Import**       | 4           | Pick file, show chunking progress, confirm upload             |
| 6   | **Download / Export**     | 4           | Download progress, chunk reassembly indicator                 |
| 7   | **Transfer Queue**        | 4           | Active uploads/downloads list with pause/resume/cancel        |
| 8   | **Sync Dashboard**        | 5           | Overall sync health, pending/failed/uploaded counts           |
| 9   | **Backup & Restore**      | 5           | Create DB backup, restore from Telegram, last backup info     |
| 10  | **Settings**              | 6           | Bot token, channel info, encryption toggle, passphrase, theme |
| 11  | **Security / Encryption** | 6           | Encryption status, change passphrase, key info                |
| 12  | **Storage Stats**         | 7           | Used space on Telegram, local cache size, file type breakdown |
| 13  | **About / Help**          | 7           | Version, licenses, how‑it‑works explainer                     |

---

## PHASE 1 — Project Setup & App Shell ✅ COMPLETED

### 1.1 Goals

- ✅ Freeze architecture, set up project, and build the app shell with navigation

### 1.2 Backend Tasks

- ✅ Create Android project (Kotlin, Compose, Material 3)
- ✅ Configure Gradle: R8, resource shrinking, min SDK 26
- ✅ Add core dependencies (Room, WorkManager, OkHttp, Kotlin Serialization)
- ✅ Set up clean architecture packages: `data/`, `domain/`, `ui/`, `common/`
- ✅ Define Room database class (4 tables: files, chunks, sync_state, metadata)

### 1.3 Frontend Tasks — Screens Built

- ✅ **Splash / Welcome Screen** — animated cloud icon + tagline, auto‑navigate to onboarding or home
- ✅ **App Shell** — `Scaffold` with `TopAppBar`, `NavigationBar` (Home, Transfers, Settings)
- ✅ **Theme Setup** — Material 3 dynamic color, light/dark mode, typography scale (seed #1B6EF3)
- ✅ Navigation graph with all 13 route stubs (placeholder screens)

### 1.4 Deliverables

- ✅ Buildable app with navigation between all stub screens
- Architecture diagram (data flow + sync flow)
- Threat model document

### 1.5 Success Criteria

- ✅ App compiles, launches, navigates between stubs
- ✅ Material 3 theming works in light + dark mode
- ✅ Tested on RMX3785 (Realme) running Android 15

---

## PHASE 2 — Telegram Bot + Channel Setup ✅ COMPLETED

### 2.1 Goals

- ✅ Working Telegram connection with bot + private channel
- ✅ User can set up their bot inside the app

### 2.2 Backend Tasks

- ✅ Implement `TelegramApiService` (OkHttp): `getMe`, `sendMessage`, `deleteMessage`, `sendDocument`, `getFile`, `downloadFile`
- ✅ Validate bot token via `getMe` call
- ✅ Upload test file to channel via `sendDocument`
- ✅ Download file via `getFile` → `file_path` URL
- ✅ Store bot token encrypted (AES-256-GCM via Android Keystore) in Room `metadata` table
- ✅ Store `chat_id`, `message_id`, `file_id`, `file_unique_id` per upload
- ✅ Repository: `TelegramRepository` exposing suspend functions
- ✅ `CryptoManager` — AES-256-GCM encryption/decryption using Android Keystore
- ✅ `TelegramModels` — Kotlin Serialization data classes for all Bot API responses

### 2.3 Frontend Tasks — Screens Built

- ✅ **Onboarding Screen (3 steps)** with animated step transitions
  - ✅ Step 1: "How it works" — cloud icon + 3 feature cards (Bot, Channel, Encryption)
  - ✅ Step 2: "Enter Bot Token" — `OutlinedTextField`, paste support, show/hide toggle, "How to create a bot?" link to @BotFather
  - ✅ Step 3: "Verify Channel" — channel ID input, setup instructions card, verify via send+delete test message
- ✅ **Onboarding states**: loading `CircularProgressIndicator`, error `Snackbar` with retry, success card with bot name / channel verified
- ✅ Bot token input uses `PasswordVisualTransformation` to mask token
- ✅ Splash screen wired to check Room DB `onboarding_completed` → routes to Onboarding or Home

### 2.4 Success Criteria

- ✅ User completes onboarding and reaches Home
- ✅ App validates token via `getMe` and verifies channel via `sendMessage`+`deleteMessage`
- ✅ Configuration persists in encrypted Room metadata (survives app restart)
- ✅ Onboarding shows clear errors for invalid token or channel
- ✅ Tested on RMX3785 (Realme) running Android 15

---

## PHASE 3 — Local Database & File Browser ✅ COMPLETED

### 3.1 Goals

- ✅ Full offline file browsing from Room DB
- ✅ Home screen populated with real data

### 3.2 Backend Tasks

- ✅ Implement full Room schema (already done in Phase 1):
  - `files` — id, name, size, mimeType, sha256, encryptionFlag, localUri, createdAt, updatedAt
  - `chunks` — id, fileId (FK), chunkIndex, telegramMessageId, checksum, size
  - `sync_state` — fileId (FK), status (pending_upload | uploaded | failed | deleted), lastAttempt
  - `metadata` — key‑value for bot token (encrypted), chat_id, settings
- ✅ Room DAOs with `Flow` returns for reactive UI
- ✅ Repository: `FileRepository` (importFile, query, search by name/type, delete with local cleanup)
- ✅ Local file import: copy from content URI to app‑private storage, compute SHA‑256 hash
- ✅ Thumbnail generation for images (`ThumbnailUtil` using BitmapFactory downscale, 256px, JPEG cache)
- ✅ `NetworkMonitor` — real-time connectivity tracking via `ConnectivityManager.NetworkCallback` as `Flow<Boolean>`

### 3.3 Frontend Tasks — Screens Built

- ✅ **Home — File Browser**
  - ✅ `LazyVerticalGrid` / `LazyColumn` toggle (grid vs list view)
  - ✅ `SearchBar` (M3) at top — filters by name, type
  - ✅ `FilterChip` row — All, Images, Docs, Videos, Audio
  - ✅ Each file item: MIME icon, name, size, sync badge (Uploaded/Pending/Failed)
  - ✅ `FloatingActionButton` → navigate to Upload
  - ✅ **Empty state**: folder icon + "Upload your first file" CTA
  - ✅ **Offline indicator**: animated banner "You're offline" with cloud-off icon
  - ✅ Pull‑to‑refresh via `PullToRefreshBox`
  - ✅ `HomeViewModel` with sealed UI state + `ViewModelProvider.Factory`
- ✅ **File Detail / Preview Screen**
  - ✅ File name, size, type, created/modified dates (relative time)
  - ✅ Sync status `AssistChip` (uploaded / pending / failed / deleted)
  - ✅ MIME-type icon preview in card
  - ✅ Metadata card: size, type, created, modified, SHA-256, encrypted status
  - ✅ Actions: Download button, Delete button with `AlertDialog` confirmation
  - ✅ **Error state** with retry button, **Loading state**
  - ✅ `FileDetailViewModel` with sealed `FileDetailUiState`

### 3.4 Success Criteria

- ✅ Home shows files from Room DB, works fully offline
- ✅ Search and filter work instantly (reactive Flow)
- ✅ File detail shows all metadata and sync state
- ✅ Empty, error, and loading states display correctly
- ✅ Tested on RMX3785 (Realme) running Android 15

---

## PHASE 4 — Chunked Upload & Download + Transfer UI (≈ 2–3 weeks)

### 4.1 Goals

- Large file support via chunking
- Real‑time upload/download progress in UI

### 4.2 Backend Tasks

- Chunk strategy: default 20 MB per chunk (configurable), split before upload
- `ChunkManager`: split file → encrypt chunk → upload via `sendDocument` → store `message_id`
- Per‑chunk checksum (SHA‑256) stored in `chunks` table
- Download: fetch chunks by `message_id` order → verify checksum → reassemble → verify full file hash
- Handle partial uploads: track last successful chunk index, resume from there
- Retry per‑chunk with backoff on failure
- `TransferManager` coordinating active uploads/downloads with coroutine flows
- Emit progress as `StateFlow<TransferProgress>` (bytesTransferred, totalBytes, currentChunk, totalChunks)

### 4.3 Frontend Tasks — Screens Built

- **Upload / Import Screen**
  - File picker button (Android `ACTION_OPEN_DOCUMENT`)
  - Selected file preview: name, size, estimated chunks count
  - "Upload" button → `LinearProgressIndicator` with percentage + chunk counter ("Chunk 3/7")
  - Cancel button during upload
  - Success: `Snackbar` + navigate to File Detail
  - Failure: inline error with retry
- **Download / Export Screen** (or modal from File Detail)
  - `LinearProgressIndicator` with chunk reassembly status
  - "Save to device" after completion
  - Error + retry
- **Transfer Queue Screen** (accessible from bottom nav)
  - `LazyColumn` of active and recent transfers
  - Each item: file name, progress bar, status chip (uploading / downloading / paused / failed / done)
  - Swipe‑to‑cancel on active transfers
  - Empty state: "No active transfers"

### 4.4 Success Criteria

- 200 MB+ file uploads and downloads successfully with chunks
- Progress updates smoothly in real time
- Interrupted transfer resumes from last chunk
- Transfer Queue reflects all active and recent jobs

---

## PHASE 5 — Sync Engine, Backup & Sync Dashboard (≈ 2–3 weeks)

### 5.1 Goals

- Reliable background sync with WorkManager
- User can back up DB and restore on new device

### 5.2 Backend Tasks

- Define sync states: `pending_upload` → `uploaded` → `failed` / `deleted`
- `SyncWorker` (WorkManager): process pending uploads in background
  - Constraints: `NetworkType.CONNECTED`, retry with exponential backoff
  - Respect battery and doze mode
- `CleanupWorker`: remove orphaned chunks and temp files
- Remote verification: compare local chunk list against Telegram messages
- **DB Backup**: encrypt Room snapshot (AES‑GCM) → upload as document → store backup `message_id`
- **DB Restore**: download backup by `message_id` → decrypt → replace local DB → restart
- `SyncRepository` exposing sync stats as `Flow` (pendingCount, uploadedCount, failedCount)

### 5.3 Frontend Tasks — Screens Built

- **Sync Dashboard Screen**
  - Summary cards: "X files pending", "Y uploaded", "Z failed"
  - `LinearProgressIndicator` for overall sync progress
  - "Sync Now" button (manual trigger)
  - Failed files list with per‑file retry
  - Last sync timestamp
  - **Offline banner**: "Waiting for connection…"
- **Backup & Restore Screen** (sub‑screen of Settings)
  - "Create Backup" button → progress → success with timestamp
  - "Restore from Telegram" button → warning dialog → progress → restart prompt
  - Last backup date and size
  - **Empty state**: "No backups yet"

### 5.4 Success Criteria

- Background sync uploads pending files without user interaction
- Sync Dashboard shows accurate real‑time counts
- DB backup + restore works on a fresh install
- Sync resumes after crash, reboot, or network loss

---

## PHASE 6 — Security, Encryption & Settings (≈ 1–2 weeks)

### 6.1 Goals

- All data encrypted before leaving device
- User has full control via Settings

### 6.2 Backend Tasks

- Encrypt every chunk before upload (AES‑256‑GCM)
- Encrypt DB snapshots before backup upload
- Master key stored in Android Keystore (hardware‑backed when available)
- Optional user passphrase: derive wrapping key via PBKDF2 → wrap master key
- Secure deletion: overwrite + delete temp files and decrypted cache
- Never log bot token or encryption keys

### 6.3 Frontend Tasks — Screens Built

- **Settings Screen**
  - Section: **Telegram** — bot token (masked, tap to reveal), channel name, "Re‑verify" button
  - Section: **Storage** — local cache size, "Clear Cache" button
  - Section: **Sync** — auto‑sync toggle, sync frequency (Wi‑Fi only toggle)
  - Section: **Security** — navigate to Security screen
  - Section: **Appearance** — theme (System / Light / Dark), dynamic color toggle
  - Section: **About** — navigate to About screen
- **Security / Encryption Screen**
  - Encryption status: enabled ✓ / disabled ✗
  - "Set Passphrase" / "Change Passphrase" — `OutlinedTextField` (password)
  - Key info: key creation date, hardware‑backed badge
  - "Wipe All Data" danger button with confirmation dialog

### 6.4 Success Criteria

- Uploaded files are unreadable outside the app
- Passphrase change works without losing data
- Settings reflect actual state and all toggles work
- "Wipe All Data" fully clears local + offers to delete remote

---

## PHASE 7 — Storage Stats, About & UI Polish (≈ 1–2 weeks)

### 7.1 Goals

- Complete remaining screens
- Full UI polish pass to match Google‑app quality

### 7.2 Frontend Tasks — Screens Built

- **Storage Stats Screen**
  - Donut chart or bar: used space on Telegram by file type
  - Local cache size
  - Total files count, total chunks count
  - "Largest files" quick list
- **About / Help Screen**
  - App version, build number
  - "How it works" explainer (bot + channel diagram)
  - Open‑source licenses
  - Link to project repo (if open‑source)

### 7.3 UI Polish Tasks (All Screens)

- Consistent M3 spacing, padding, and elevation across all screens
- Smooth animations: screen transitions, progress bars, list item appear
- Haptic feedback on key actions (upload start, delete confirm)
- Accessibility: content descriptions, minimum touch targets (48dp)
- Landscape and tablet layout considerations
- Edge‑to‑edge display support
- Consistent icon usage (Material Icons only)

### 7.4 Backend Tasks

- Compute storage stats from Room queries
- Expose stats as `Flow` for reactive UI updates

### 7.5 Success Criteria

- All 13 screens fully built and functional
- UI feels like a polished Google app (Files / Keep quality)
- No visual glitches in light or dark mode

---

## PHASE 8 — Minimal App Size & Performance (≈ 1 week)

### 8.1 Goals

- Smallest possible APK
- Smooth on mid‑range devices

### 8.2 Tasks

- Audit and remove unused dependencies
- Verify R8 + resource shrinking is active
- Strip unused `kotlinx` modules
- Optimize image loading (downscale thumbnails, no heavy image library unless needed)
- Measure and optimize: cold start < 1s, memory < 100 MB under load
- Profile with Android Studio profiler

### 8.3 Success Criteria

- APK < 10 MB target (stretch: < 7 MB)
- Cold start under 1 second
- No jank on file list scroll with 500+ items

---

## PHASE 9 — Hardening & QA (≈ 1–2 weeks)

### 9.1 Goals

- Boringly stable app

### 9.2 Tasks

- Battery optimization: respect Doze, App Standby
- Stress test: upload 100+ files, 1 GB+ chunked file
- Corruption recovery: detect bad chunks, re‑download
- Token expiry / revocation handling
- Edge cases: no storage space, airplane mode mid‑transfer, rapid config changes
- Full UI walkthrough on Android 8, 10, 12, 14+
- Screen‑reader and TalkBack pass

### 9.3 Success Criteria

- Zero critical crashes across all test scenarios
- Reliable recovery from every tested failure

---

## PHASE 10 — Release & Distribution (≈ 1 week)

### 10.1 Goals

- Safe public release

### 10.2 Tasks

- Write privacy policy (Telegram usage, data handling, no telemetry)
- Store listing: screenshots of all key screens, feature graphic, description
- Google Play internal testing track → closed beta → production
- Optional open‑source release (clean repo, LICENSE, README, contributing guide)
- Set up crash reporting (optional, privacy‑respecting only)

---

# 🔧 Tech Stack Decision (Expo or Other?)

**Recommendation:** Do **not** use Expo.

Reasons:

- We need direct Android system APIs (storage, encryption, WorkManager)
- Telegram Bot API is HTTP based, but large file chunking, background sync, and keystore access are best on native Android
- Material 3 Compose is native and gives the best UI fidelity
- Minimal app size is easier with native Android and fewer JS dependencies

**Preferred stack:**

- Android native (Kotlin)
- Jetpack Compose + Material 3
- Room + WorkManager
- OkHttp + Kotlin serialization

If you still want a cross‑platform option, consider **React Native (bare)**, but it increases size and complexity and is not ideal for strict minimal‑size goals.

---

# ✅ Best Tech Stack (Final)

**Frontend (UI/UX)**

- Kotlin + Jetpack Compose
- Material 3 (official Google library only)
- Navigation Compose + Material icons

**Backend (Storage + Sync Layer)**

- Telegram Bot API (official docs only)
- Room (SQLite) as source of truth
- WorkManager for background sync
- OkHttp + Kotlin serialization for API and chunk upload

**Security & Storage**

- AES‑256‑GCM for file and DB encryption
- Android Keystore for key protection
- Chunked file upload/download with integrity checks

**Build & Size**

- R8 + resource shrinking
- Avoid heavy media or analytics SDKs
- Minimal permissions and dependencies

---

# 🤖 Copilot Instructions (Project Rules)

Follow these rules strictly when generating code or plans for this project:

1. **Follow the roadmap phases exactly** and keep tasks aligned to the current phase.
2. **Use the approved tech stack only** (Kotlin, Compose, Material 3, Room, WorkManager, OkHttp).
3. **Use Telegram Bot API only** per https://core.telegram.org/ — no custom servers.
4. **Develop frontend and backend in parallel** — every phase builds its UI screens and backend logic together. Never defer UI to a later phase.
5. **Build exactly the screens listed per phase** — see Phase 0.4 Screen Map for the master list.
6. **Offline‑first always** — every screen must show data from Room, handle empty state, error state with retry, and offline indicator.
7. **Chunk large files** and store per‑chunk Telegram `message_id` values.
8. **Keep app size minimal** — avoid heavy libraries and unused dependencies. Target APK < 10 MB.
9. **Material 3 UI only** — use `TopAppBar`, `NavigationBar`, `Card`, `ListItem`, `FAB`, `SearchBar`, `FilterChip`, `LinearProgressIndicator`, `Snackbar`, `AlertDialog` from M3. Match the feel of Google Files / Keep / Drive.
10. **Security first** — encrypt all data before upload and use Android Keystore.
11. **Write production‑quality code** — clean architecture (repository pattern), sealed classes for UI state, `StateFlow` for reactive UI, named arguments, explicit Room migrations.
12. **Every screen must have**: loading state, populated state, empty state, error state with retry action.
