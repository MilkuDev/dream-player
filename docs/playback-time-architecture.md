## Related documents

This document specifies only playback time architecture.

For the overall project architecture see:

Architecture-Snapshot.md

# Playback Time Architecture

---

# Purpose

This document defines the playback time architecture used throughout the project.

Its purpose is to describe how playback timing flows through the system, define component responsibilities, and provide implementation guidance for all supported platforms.

This document complements (but does not replace) the architectural invariants stored in the project's MCP Memory.

---

# Goals

The playback architecture must satisfy the following goals:

- Maintain a single authoritative playback timeline.
- Eliminate duplicated playback timing logic.
- Remove UI jitter caused by competing playback clocks.
- Keep playback timing independent of UI frameworks.
- Support Kotlin Multiplatform.
- Require minimal platform-specific code.
- Be easily extensible for future playback engines.

---

# Design Principles

The playback timeline originates exclusively from the platform audio engine.

The application never reconstructs playback time.

Instead, every consumer observes the playback timeline through a dedicated abstraction.

The architecture intentionally separates:

- application state
- playback timing

These are independent concerns.

---

# High-Level Architecture

```
                  Audio Engine
      (Media3 / AVPlayer / JVM Audio)

                     │
         ┌───────────┴───────────┐
         │                       │
         ▼                       ▼
 PlaybackState          PlaybackTimeSource
         │                       │
         ▼                       │
 PlayerViewModel                │
         │                       │
         ▼                       ▼
 PlaybackUiState     UI / SavePoints / Widgets / Services
```

---

# Playback Pipeline

```
Audio Engine
      │
      ▼
PlaybackTimeSource
      │
      ▼
PlaybackTimeSnapshot
      │
      ├────────────► PlayerProgress
      │
      ├────────────► SavePoint Worker
      │
      ├────────────► Widgets
      │
      ├────────────► Notifications
      │
      └────────────► Future consumers
```

Every playback time consumer receives timing information from exactly one place.

No alternative playback timeline exists.

---

# Component Responsibilities

## Audio Engine

Examples:

- Media3 ExoPlayer
- AVPlayer
- JVM playback engine

Responsibilities:

- playback
- playback position
- buffering
- playback speed
- playback state
- duration

The audio engine is the only owner of playback time.

---

## PlaybackTimeSource

Application abstraction over playback timing.

Responsibilities:

- expose immutable playback snapshots;
- hide platform-specific playback APIs;
- provide consistent timing information.

Responsibilities explicitly excluded:

- business logic;
- rendering;
- scheduling;
- UI notifications;
- playback interpolation;
- playback extrapolation.

Public API:

```kotlin
interface PlaybackTimeSource {

    fun snapshot(): PlaybackTimeSnapshot
}
```

---

## PlaybackTimeSnapshot

Represents one atomic playback state.

Example:

```kotlin
data class PlaybackTimeSnapshot(
    val positionMs: Long,
    val durationMs: Long,
    val bufferedPositionMs: Long,
    val playbackSpeed: Float,
    val isPlaying: Boolean,
)
```

Snapshots should always represent a single instant in time.

Consumers should never reconstruct playback timing from multiple independent getters.

---

## PlaybackState

Represents discrete playback events.

Examples:

- play
- pause
- buffering
- track changed
- queue updated
- repeat mode
- shuffle mode

PlaybackState is **not** a playback clock.

---

## PlaybackUiState

Represents UI state only.

Typical fields:

- current track
- queue
- queue index
- repeat mode
- shuffle mode
- presentation state
- navigation state

PlaybackUiState intentionally contains no continuously advancing playback position.

---

## PlayerViewModel

Responsible for application logic.

Responsibilities:

- queue
- playback commands
- repeat
- shuffle
- navigation
- interaction with AudioPlayer

PlayerViewModel does not own playback timing.

---

## UI

Responsible only for presentation.

The UI reads PlaybackTimeSnapshot whenever it needs fresh playback timing.

The refresh strategy depends entirely on the platform.

Examples include:

- frame-driven polling
- native callbacks
- timers

The UI never reconstructs playback time.

---

## SavePoint Worker

Responsible for periodically persisting playback position.

Playback timing is obtained directly from PlaybackTimeSource.

The save worker is completely independent of UI state.

---

# Platform Implementations

## Android

Implementation:

```
Media3 ExoPlayer
        │
        ▼
AndroidPlaybackTimeSource
```

Recommended UI strategy:

Frame-driven rendering.

The UI reads PlaybackTimeSnapshot every frame while playback is active.

---

## Desktop

Implementation:

```
JVM Audio Engine
        │
        ▼
JvmPlaybackTimeSource
```

Rendering strategy may differ.

---

## iOS

Implementation:

```
AVPlayer
     │
     ▼
IosPlaybackTimeSource
```

Rendering strategy is implementation-specific.

---

# Rendering Strategy

The architecture deliberately does not prescribe how UI refreshes itself.

Only one requirement exists:

Whenever playback timing is required, it must be obtained directly from PlaybackTimeSource.

Platform-specific refresh strategies must never affect the public PlaybackTimeSource contract.

---

# Data Flow

Playback timing:

```
Audio Engine
      │
      ▼
PlaybackTimeSource
      │
      ▼
PlaybackTimeSnapshot
      │
      ▼
UI
```

Application state:

```
Audio Engine
      │
      ▼
PlaybackState
      │
      ▼
PlayerViewModel
      │
      ▼
PlaybackUiState
      │
      ▼
Compose UI
```

These flows intentionally remain independent.

---

# Migration Notes

Legacy architecture:

```
AudioPlayer
      │
      ▼
ViewModel
      │
      ▼
PlaybackUiState.playbackProgressMs
      │
      ▼
Compose interpolation
```

Target architecture:

```
Audio Engine
      │
      ▼
PlaybackTimeSource
      │
      ▼
PlaybackTimeSnapshot
      │
      ▼
UI
```

Removed concepts:

- playbackProgressMs
- startProgressUpdates()
- anchor position
- drift compensation
- local playback clock
- playback extrapolation
- playback interpolation
- periodic ViewModel progress updates

---

# Examples

## Correct

```kotlin
val snapshot = playbackTimeSource.snapshot()

slider.value = snapshot.positionMs
```

---

## Incorrect

```kotlin
position += elapsedTime
```

---

## Correct

```
PlaybackTimeSource
      │
      ▼
SavePoint Worker
```

---

## Incorrect

```
PlaybackUiState
      │
      ▼
SavePoint Worker
```

---

# Future Extensions

The architecture should naturally support:

- playback speed changes;
- buffered progress rendering;
- live streams;
- unknown durations;
- gapless playback;
- video playback;
- additional playback engines.

No architectural changes should be required to support these features.

---

# Out of Scope

This document intentionally does not define:

- queue architecture;
- metadata pipeline;
- MediaSession implementation;
- notification UI;
- playlist management;
- persistence architecture;
- Compose implementation details.

Those concerns are documented separately.