# DreamPlayer

DreamPlayer is a local music player built from the ground up using **Kotlin Multiplatform** and **Compose Multiplatform**. It targets **Android** and **Desktop (Windows/macOS/Linux JVM)**, providing a unified, high-performance audio experience across devices without relying on heavy external frameworks.

---

## Architecture & Core Philosophy

The project is designed to be lightweight, modular, and explicit, avoiding heavy third-party abstractions where native language features suffice.

* **Zero DI Frameworks:** No Koin or Hilt. The project relies entirely on Kotlin's native `expect`/`actual` mechanism for platform-specific abstractions, keeping compile times fast and dependency graphs transparent
* **Custom Multiplatform State Management:** Driven by a custom architectural implementation via `PlayerViewModel`, specifically tailored for multiplatform predictability rather than sticking strictly to platform-bound patterns like Android Jetpack ViewModel.
* **Decoupled Playback Engine:** The playback layer is completely isolated from the database layer. The audio player receives clean data snapshots (`PlaybackSnapshot`), eliminating direct database queries during time-critical audio streaming.
* **Secure Network Policy:** Network communication goes through Ktor and is governed by a strict policy that enforces HTTPS and explicitly whitelists trusted hosts (e.g., MusicBrainz, Last.fm, OpenAI, Gemini, DeepSeek).

### Architecture Flowchart

---

## Tech Stack & Core Libraries

* **UI & Theming:** Compose Multiplatform with Material 3.
* **Database:** Room (SQLite) with a bundled driver ensuring uniform behavior across platforms. Writes are optimized using Write-Ahead Logging (WAL) mode to keep the UI perfectly smooth.
* **Settings:** DataStore Preferences for simple, lightweight configuration storage.
* **Network & Parsing:** Ktor Client combined with `kotlinx.serialization` for JSON payloads.
* **Platform Audio (Android):** Media3 ExoPlayer coupled with `MediaSession` for flawless background audio and native system integration.
* **Platform Audio (Desktop):** Java Sound API utilizing custom Service Provider Interfaces (`mp3spi` and `flannel` for native FLAC decoding).

---

## Project Structure

The codebase isolates platform mechanics into distinct launcher modules while sharing core implementation logic:

```text
KotlinMPDreamPlayer/
├── androidApp/          # Thin Android application launcher & assets configuration
├── desktopApp/          # Thin Desktop application launcher (Main.kt)
└── composeApp/          # Core multiplatform module
    ├── schemas/         # Room DB schema version history (1.json to 7.json)
    └── src/
        ├── commonMain/  # Main shared codebase (UI, ViewModels, Room DB setup, Repositories)
        ├── androidMain/ # MediaStore scanner, Media3 audio player, Android-specific secure storage
        └── jvmMain/     # Local directory file scanner, Java Sound audio player, desktop paths
```

---

## Core Subsystems

### 1. Music Scanning & Sync
The application bypasses common platform limitations by executing specialized scanning strategies:
* **Android (`MediaStoreScanner`):** Queries the system's content provider (`IS_MUSIC != 0`), hooks into native `content://` URIs, and actively observes media library updates using a `ContentObserver`.
* **Desktop (`JvmMusicScanner`):** Directly walks through the `~/Music` directory, decodes audio file headers for metadata, and automatically scans for sidecar artwork files (e.g., `cover.jpg`, `folder.png`, `front.webp`).

**Smart Syncing (`MusicRepository`):**
When raw tracks are collected, they are processed in batches into the Room database. Tracks are matched not just by file path, but by a unique signature (**Title + Artist + Duration + File Size**). This prevents losing track history, user stats, or playlist assignments if an audio file is renamed or moved across folders.

### 2. Database Schema
The Room SQLite database maps out the entire catalog structure across the following primary tables:
* `artists` / `albums` / `library_tracks`: Core catalog items containing basic tracking details and availability flags.
* `playlists` / `playlist_track_cross_ref`: Handles user-defined and system lists along with strict track positioning.
* `genres` / `album_genre_cross_ref` / `track_genre_cross_ref`: Cross-referenced genre tagging.
* `sync_audit`: Tracks indexing history, performance counters, and error boundaries.
* Metadata state tables: Tracks synchronization completeness and data confidence metrics.

### 3. Metadata & Artwork Enrichment
Enrichment runs as a background process orchestrated by `MetadataSyncService` using a strict, multi-tiered trust system:
1.  **Local Embedded Tags:** Extracts embedded tags, including MusicBrainz Recording IDs (MBID), years, and genres.
2.  **Remote Enrichment:** Connects to MusicBrainz and Last.fm APIs via Ktor to resolve missing dates or genres, adhering to strict API rate-limiting.
3.  **Artwork Retrieval:** Looks up high-resolution imagery via the Cover Art Archive using resolved release group IDs.

Values are evaluated using a deterministic trust hierarchy (e.g., keeping an existing high-confidence tag over a low-confidence scrape).

### 4. Smart Playlist Generation (Daily Playlist)
The system playlist ("Плейлист дня") checks daily for recreation, leveraging two operating modes:
* **`LOCAL_DAILY` Mode:** Generates a curated, randomized mix directly from available records in the local database.
* **`AI_API` Mode:** Extracts up to 200 random track candidates (metadata only, no audio files) and dispatches them to a configured LLM provider (OpenAI, Gemini, or DeepSeek) along with a specialized prompt. The provider returns an optimized sequence of track IDs.
* *Fallback Safety:* If a network breakdown, quota exhaustion, or parsing error occurs, the pipeline gracefully defaults to `LOCAL_DAILY` mode and logs the diagnostic failure details to help debugging.

### 5. Playback Pipeline
To maximize predictability, the execution flow follows a strict unidirectional sequence:
1.  **Action:** The user triggers a playback action on the UI.
2.  **Queue Controller:** `PlaybackQueueController` manages track ordering, shuffling states, and tracks the current queue version.
3.  **Resolution:** `PlaybackResolver` asks `MusicLibrarySource` to fetch database records and convert raw database IDs into ready-to-play `ResolvedPlaybackItem` references (containing validated playback URIs and availability states).
4.  **Execution:** The resolved snapshot is pushed directly down to the platform's `AudioPlayer` implementation.

---

## Building & Running

### Android
Ensure you have an Android device or emulator connected.
* **macOS / Linux:**
    ```shell
    ./gradlew :androidApp:assembleDebug
    ```
* **Windows:**
    ```shell
    .\gradlew.bat :androidApp:assembleDebug
    ```

### Desktop (JVM)
* **macOS / Linux:**
    ```shell
    ./gradlew :desktopApp:run
    ```
* **Windows:**
    ```shell
    .\gradlew.bat :desktopApp:run
    ```