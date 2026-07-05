# QWEN.md — DreamPlayer

## Project overview

DreamPlayer — локальный музыкальный плеер на **Kotlin Multiplatform + Compose Multiplatform**.
Целевые платформы: **Android**, **Desktop (Windows/macOS/Linux на JVM)**. iOS/macOS нативные таргеты подготовлены, но не являются основными.

Пакет: `org.milkdev.dreamplayer`

## Tech stack

| Компонент | Версия |
|---|---|
| Kotlin | 2.3.21 |
| Compose Multiplatform | 1.12.0-alpha01 |
| Gradle | 9.6.0 (configuration cache включён) |
| AGP | 9.2.1 |
| KSP | 2.3.7 |
| Room | 2.8.4 |
| Ktor | 3.5.0 |
| Media3 (ExoPlayer) | 1.10.1 |
| kotlinx.coroutines | 1.11.0 |
| JVM toolchain | Java 21 (Azul Zulu) |
| Android compileSdk / targetSdk | 37 |
| Android minSdk | 29 |

Версии управляются через `gradle/libs.versions.toml`.

## Module structure

```
shared/          — бизнес-логика: ViewModel, репозитории, Room, playback, сеть, AI. KMP-модуль.
composeApp/      — UI-слой на Compose Multiplatform. Зависит от :shared.
androidApp/      — Android-лаунчер (MainActivity, DreamPlayerApplication).
desktopApp/      — Desktop-лаунчер (Main.kt, окно 370×750dp).
iosApp/          — iOS-обёртка (Xcode project).
```

## Architecture

- **MVI-подобный паттерн**: `PlayerViewModel` → `MutableStateFlow<PlayerUiState>` → UI наблюдает.
- **Нет DI-фреймворков** (ни Koin, ни Hilt). Платформенные абстракции через `expect`/`actual`.
- **Кастомный стейт-менеджмент** — собственный `PlayerViewModel` вместо Android ViewModel.
- **Изолированный playback-движок** — аудио-слой потребляет `PlaybackSnapshot`, без обращений к БД во время воспроизведения.
- **Навигация** — `AppNavigationState` с back-stack, без библиотек навигации.
- **Сеть** — Ktor с whitelist HTTPS-хостов (MusicBrainz, Last.fm, OpenAI, Gemini, DeepSeek).

## Source layout

```
shared/src/
  commonMain/    — ViewModel, репозитории, Room entities/DAO, playback, сеть, AI, модели
  androidMain/   — MediaStoreScanner, AudioPlayer (Media3/ExoPlayer), PlaybackService
  jvmMain/       — JvmMusicScanner, AudioPlayer (Java Sound API)
  appleMain/     — Apple-специфичные реализации

composeApp/src/
  commonMain/    — экраны: HomeScreen, LibraryScreen, PlayerScreen, PlayingQueueScreen,
                   SettingsScreen, PlaylistScreens и др.
  androidMain/   — Android-тема
  jvmMain/       — Desktop-тема
```

## Key domain packages

- `model/` — ViewModel, UI-модели, навигация
- `playback/` — AudioPlayer, PlaybackQueueController, PlaybackResolver
- `library/` — MusicRepository, MusicScanner, PlaylistRepository, SettingsRepository
- `database/` — Room entities, DAO, AppDatabase, DataStore
- `extensions/` — сеть (Ktor, MusicBrainz, Last.fm, AI), обложки, секреты
- `ui/` — экраны Compose UI
- `app/` — App.kt, Theme, Color

## Build commands

```bash
# Сборка Desktop-приложения
./gradlew :desktopApp:run

# Сборка Android-приложения (debug APK)
./gradlew :androidApp:assembleDebug

# Полная сборка всех модулей
./gradlew build

# Тесты (находятся в shared/src/commonTest/)
./gradlew :shared:allTests
```

## Testing

Тесты расположены в `shared/src/commonTest/`:
- `ComposeAppCommonTest.kt` — поиск треков, навигация, shuffle/unshuffle очереди, repeat mode, AI playlist.
- `NetworkDiagnosticsTest.kt` — тесты сетевой диагностики.

Зависимости: `kotlin("test")`, `kotlinx-coroutines-test`, `ktor-client-mock`.

Тестов в `composeApp`, `androidApp`, `desktopApp` нет.

## CI/CD

GitHub Actions (`.github/workflows/release.yaml`):
- Триггер: push тега `v*`.
- Параллельные job: Windows (EXE/MSI/ZIP), macOS (DMG/PKG/ZIP), Linux+Android (DEB/RPM/tar.gz/APK).
- Финальный job: draft GitHub Release с SHA256 checksums.
- JDK 21 (Zulu) на всех раннерах.

## Conventions

- **Язык кода**: английский (имена, комментарии).
- **Язык документации**: русский (`docs/architecture-snapshot.md`, `README.md`).
- **Форматирование**: стандартное для Kotlin, без особых отступов.
- **Импорты**: не группируются явно, используются typesafe project accessors.
- **Room**: миграции в `composeApp/schemas/`, KSP для генерации.
- **expect/actual**: используется для всего платформенно-зависимого кода.

## Important notes

- Configuration cache Gradle включён — учитывать при написании тасков.
- JVM max heap: 3072M (Gradle + Kotlin daemon).
- Проект использует `foojay-resolver` для автозагрузки JDK.
- Подробная архитектура описана в `docs/architecture-snapshot.md`.
