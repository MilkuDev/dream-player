# DreamPlayer

Local multiplatform music player with AI and network features targeting Android, iOS (soon, in development) and desktop (Windows/macOS/Linux JVM). Built on Kotlin Multiplatform, UI on Compose (Android and desktop targets) and Swift UI (apple target) (soon).

## Core Architecture

The design prioritizes explicit declarations and native language capabilities over heavy dependencies.

* **No DI Magic:** Koin, Hilt, and similar frameworks are intentionally omitted. Platform abstractions are resolved using Kotlin's native `expect`/`actual` modifiers, which keeps compile times low and the dependency graph transparent.
* **Custom State Handling:** Standard Android Jetpack ViewModels do not translate well to pure desktop environments. State management is handled through a custom `PlayerViewModel` built strictly for predictable multiplatform execution.
* **Isolated Playback Engine:** Audio streaming and database operations are strictly separated. The playback layer consumes pre-resolved `PlaybackSnapshot` instances, preventing background database I/O from causing UI stutters or audio artifacts.
* **Network Security Policy:** All external traffic is routed through Ktor under a strict HTTPS whitelist. Connections are only permitted to explicitly trusted APIs (MusicBrainz, Last.fm, OpenAI, Gemini, DeepSeek).

## Technical Stack

* **UI Interface:** Compose Multiplatform (Material 3) and Swift UI (liquid glass).
* **Storage:** Room backed by a bundled driver. Write-Ahead Logging (WAL) is enabled to ensure concurrent read/write operations without blocking.
* **Preferences:** DataStore.
* **Networking:** Ktor Client paired with `kotlinx.serialization`.
* **Android Audio:** Media3 ExoPlayer integrated with `MediaSession`.
* **Desktop Audio:** Java Sound API utilizing custom Service Provider Interfaces (`mp3spi` and `flannel` for FLAC decoding).
* **Apple Audio:** AVPlayer

## Source Structure

Platform-specific entry points are kept minimal. The vast majority of the logic resides in the shared module.

    KotlinMPDreamPlayer/
    ├── androidApp/          (Minimal Android launcher)
    ├── desktopApp/          (Minimal Desktop launcher)
    └── composeApp/          (Compose UI)
        ├── schemas/         (Room DB migration files)
        └── src/
            ├── commonMain/  (ViewModels, Repositories, DB setup)
            ├── androidMain/ (MediaStore API, Media3, Android storage)
            └── jvmMain/     (Local file walking, Java Sound API)

## Subsystem Details

### 1. File Synchronization
OS limitations require different strategies for discovering audio files:
* **Android:** Queries `MediaStore` (`IS_MUSIC != 0`) and listens for real-time file system changes via `ContentObserver`.
* **Desktop:** Executes a recursive traversal of the `~/Music` directory, parsing file headers and detecting associated artwork sidecars (`cover.jpg`, etc.).

To prevent library corruption when files are moved or renamed, `MusicRepository` calculates a unique hash signature (Title + Artist + Duration + File Size). This guarantees that playback history and playlist positions remain stable regardless of file path modifications.

### 2. Relational Database
The Room schema maintains the local catalog via interconnected tables:
* `artists`, `albums`, `library_tracks`: Core metadata storage.
* `playlists`, `playlist_track_cross_ref`: Custom user mixes enforcing strict track sequence.
* `genres`, `album_genre_cross_ref`, `track_genre_cross_ref`: Genre associations.
* `sync_audit`: Indexing performance logs and error states.

### 3. Metadata Processing
`MetadataSyncService` resolves missing file tags through a strict hierarchy:
1. **Local Priority:** Embedded file tags (MBIDs, release years) are the ultimate source of truth.
2. **Remote Lookups:** Missing data triggers API calls to MusicBrainz and Last.fm, strictly respecting rate limits.
3. **Artwork Retrieval:** Missing covers are downloaded from the Cover Art Archive.
Remote data is never permitted to overwrite existing, high-confidence local tags.

### 4. Smart Playlists
The daily mix generates via two distinct pathways:
* **AI Curation:** Extracts basic metadata for a sample of ~200 local tracks and requests an optimized listening sequence from an LLM provider (OpenAI, Gemini, DeepSeek).
* **Local Fallback:** If network conditions degrade or API limits are reached, the system instantly switches to a randomized, offline curation algorithm.

### 5. Playback Pipeline
State mutations flow in a single direction:
1. A playback intent is dispatched from the UI.
2. `PlaybackQueueController` applies shuffle algorithms and assigns a new queue version.
3. `PlaybackResolver` maps raw database IDs into validated `ResolvedPlaybackItem` references.
4. The resolved snapshot is passed directly to the native `AudioPlayer` implementation.

## Build

Download compiled binaries directly from the [releases page](https://github.com/MilkuDev/dream-player/releases). 
To build from source, clone the repository and execute the platform-specific commands below.

### Clone the Repository
```bash
git clone [https://github.com/MilkuDev/dream-player.git](https://github.com/MilkuDev/dream-player.git)
cd KotlinMPDreamPlayer
```

### Android
Requires a connected Android device or an active emulator.
* **macOS / Linux:**
  ```bash
  ./gradlew :androidApp:assembleDebug
  ```
  or
  
  ```bash
  ./gradlew :androidApp:assembleRelease
  ```
* **Windows:**
  ```powershell
  .\gradlew.bat :androidApp:assembleDebug
  ```
  or
  
  ```powershell
  .\gradlew.bat :androidApp:assembleRelease
  ```

### Desktop (JVM)
* **macOS / Linux:**
  ```bash
  ./gradlew :desktopApp:createDistributable 
  ```
* **Windows:**
  ```powershell
  .\gradlew :desktopApp:createDistributable 
  ```
