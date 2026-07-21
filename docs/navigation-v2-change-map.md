# DreamPlayer Navigation v2 — карта изменений

Статус: реализовано 2026-07-21; документ сохранён как карта принятого решения.

Связанная спецификация: `docs/navigation-v2-spec.md`.

## 1. Итоговый контур

```text
Compose UI
    -> semantic callbacks PlayerViewModel
        -> immutable AppNavigationState
            -> StateFlow<AppNavigationState>
                -> Compose rendering
                -> content activation в PlayerViewModel
```

Навигационная модель отвечает только за routes, entries, stack operations и структурные инварианты. `PlayerViewModel` проверяет внешние условия, активирует данные и управляет подписками. Compose интерпретирует content и overlays.

## 2. Карта файлов и компонентов

### Новая navigation package в `shared/commonMain`

Целевое расположение:

```text
shared/src/commonMain/kotlin/org/milkdev/dreamplayer/navigation/
    AppRoute.kt
    NavigationEntry.kt
    AppNavigationState.kt
```

| Компонент | Сейчас | Целевое состояние |
|---|---|---|
| `Screen` | enum в `playback/PlayerUiState.kt` | удаляется; UI работает с `AppRoute`/`NavigationEntry` |
| `AppDestination` | internal enum в конце `PlayerViewModel.kt` | заменяется `AppRoute` с route arguments |
| `AppNavigationState` | хранит `List<AppDestination>` внутри файла ViewModel | отдельный pure Kotlin тип, хранит `List<NavigationEntry>` |
| Mapping-функции | `Screen <-> AppDestination` | удаляются |
| Entry identity | отсутствует | стабильный `entryId` для каждого входа |
| Main selection | выводится из `Screen` вручную | `activeMainDestination` вычисляется navigation state |
| Back preview | отсутствует | чистое вычисление preview state без публикации |

### `PlayerViewModel.kt`

| Текущая область | Изменение |
|---|---|
| `private var navigationState` | заменить на приватный `MutableStateFlow<AppNavigationState>` и публичный read-only StateFlow |
| `navigateTo(Screen)` | удалить из публичного UI API |
| `navigateBack()` | оставить semantic-командой; публиковать результат `pop` только после успешного перехода |
| `openPlayer()` | проверяет current track, затем просит navigation state добавить Player |
| `openQueueSheet()` | вызывает структурно допустимое добавление Queue; обновление display queue остаётся orchestration |
| `openPlaylist()` | создаёт `Playlist(id)` entry, затем активирует данные этого entry |
| `openAlbumDetails()` | создаёт `LibraryCollection(ALBUM, id)` entry |
| `openArtistDetails()` | создаёт `LibraryCollection(ARTIST, id)` entry |
| `openGenreDetails()` | создаёт `LibraryCollection(GENRE, id)` entry |
| `openDailyPlaylist()` | использует `Playlist(SystemPlaylists.DailyPlaylist.id)` |
| `openLibrarySearch()` | удаляет detail suffix, сохраняет Home/Library anchor и добавляет Search |
| `navigateTo(Settings)` | заменить `openSettings()` |
| `navigateTo(AiDebugSettings)` | заменить `openAiDebugSettings()` |
| `setNavigationState()` | разделить на публикацию navigation state и активацию content entry |
| detail cleanup по `contains(destination)` | заменить сравнением предыдущего и нового `currentContentEntry` |
| прямое изменение navigation в `clearQueue()` и AudioPlayer collector | проводить через одну функцию публикации нового navigation state |

### `PlayerUiState.kt`

Целевая карта:

- удалить `Screen`;
- удалить navigation-derived поля из `PlaybackUiState`: `currentScreen`, `canNavigateBack`, `playerPresentation`, `isQueueSheetVisible`;
- Player/Queue visibility выводить из `AppNavigationState` и наличия current track;
- удалить navigation helpers старого `PlayerUiState` после переноса тестов;
- не возвращать единый монолитный `PlayerUiState` в production.

Отдельный navigation StateFlow устраняет два источника истины: `navigationState` и его копию внутри `PlaybackUiState`.

### `App.kt`

| Сейчас | Целевое состояние |
|---|---|
| собирает три StateFlow | дополнительно собирает navigation StateFlow |
| `PlatformBackHandler` читает `playbackState.canNavigateBack` | читает `navigationState.canNavigateBack` |
| `AnimatedContent(targetState = currentScreen)` | target — `currentContentEntry`; saveable key — `entryId` |
| `when(Screen)` | `when(entry.route)` |
| Player/Queue представлены пустыми content cases | overlays исключены из content target моделью navigation state |
| Settings вызывается через generic `navigateTo` | semantic `openSettings` |
| AI Debug вызывается через generic `navigateTo` | semantic `openAiDebugSettings` |
| BottomDock получает `Screen` | получает `activeMainDestination` и semantic callbacks |

### `Components.kt` / `NavigationDock`

| Сейчас | Целевое состояние |
|---|---|
| `currentScreen: Screen` | `activeMainDestination` |
| `onNavigate(Screen)` | отдельные `onHomeClick`, `onLibraryClick` |
| Library selected через список Screen types | Library selected напрямую из `activeMainDestination` |
| Search всегда `selected = false` | Search active, когда `activeMainDestination == Search` |
| будущая AI-кнопка потребует изменения generic enum wiring | добавляется новый semantic callback и main destination |

### `HomeScreen.kt`

- `onSettingsClick` сохраняется;
- callback связывается с `PlayerViewModel.openSettings()`;
- Home rendering не знает предыдущий или следующий route.

### `LibraryScreen.kt`

- добавить `onSettingsClick` в top bar;
- существующий `LibraryIntent` продолжает передавать semantic actions для album/artist/genre/playlist;
- экран не получает generic navigator.

### `LibrarySearchScreen.kt`

- остаётся renderer route Search;
- Search route визуально использует библиотечный контент, строку поиска и фильтры;
- Back вызывает обычный `navigateBack()`;
- открытые из Search details добавляются поверх Search entry;
- query очищается только после удаления Search entry из стека.

### `PlayerOverlayHost.kt`

- перестаёт читать `playerPresentation` и `isQueueSheetVisible` из `PlaybackUiState`;
- получает derived visibility из navigation state либо отдельными параметрами;
- drag-to-dismiss продолжает вызывать `navigateBack()`;
- playback timing path не изменяется.

### `PlatformBackHandler`

Первый этап:

- common expect API не меняется;
- Android Back вызывает commit обычного `navigateBack()`;
- Desktop остаётся no-op.

Будущий Predictive Back:

- Android adapter получает preview navigation state;
- gesture cancel не публикует preview;
- gesture commit публикует тот же результат, что обычный Back;
- common navigation model не переписывается.

## 3. Карта route -> данные

| Route | Данные | Источник | Lifecycle |
|---|---|---|---|
| Home | daily playlist, history, home genre | существующий `LibraryUiState` | application lifetime |
| Library | страницы библиотеки | существующий `LibraryUiState` | application lifetime |
| Search | query, filters, search pages | `LibraryUiState.librarySearch` | очищается при удалении Search entry |
| Playlist(id) | playlist descriptor + tracks | `PlaylistRepository` | подписка активного content entry |
| Album(id) | header + tracks | `MusicLibrarySource` | подписка активного content entry |
| Artist(id) | header + tracks | `MusicLibrarySource` | подписка активного content entry |
| Genre(id) | header + albums + tracks | `MusicLibrarySource` | combine активного content entry |
| Settings | settings state | существующий `SettingsUiState` | application lifetime |
| AiDebugSettings | settings/diagnostic state | существующий `SettingsUiState` | application lifetime |
| Player/Queue | playback state и queue display | playback subsystem | application lifetime; content subscription не меняется |

## 4. Обнаруженный стык: восстановление detail header

Текущие `openAlbumDetails`, `openArtistDetails` и `openGenreDetails` получают полный list item и сразу строят `LibraryCollectionDetailsUiModel`. После перехода на route `type + id` Back должен заново активировать предыдущий entry, но текущий `MusicLibrarySource` не предоставляет единый lookup header по ID.

Варианты:

1. Добавить repository/source lookup для Album/Artist/Genre по ID.
2. Хранить лёгкий immutable header descriptor на время жизни `NavigationEntry`, а tracks получать реактивно заново.
3. Поместить title/artwork непосредственно в route — не рекомендуется, потому что navigation начнёт владеть presentation data.

Рекомендованный вариант для первого рефакторинга: вариант 2.

Ограничения descriptor store:

- принадлежит `PlayerViewModel`/экранному state, а не navigation model;
- ключ — `entryId`;
- содержит только type, entity ID, title, subtitle и artwork URI;
- tracks/albums в нём не кешируются;
- удаляется вместе с entry;
- при Back позволяет немедленно восстановить header и заново подписаться на reactive data;
- позже может быть заменён repository lookup без изменения navigation contract.

Это сохраняет текущий library API и не требует изменений всех `expect`/`actual` реализаций `MusicLibrarySource` на первом этапе.

## 5. Карта переходов

### Home / Library

```text
любое состояние -> selectMainPage(Home)    -> [Home]
любое состояние -> selectMainPage(Library) -> [Home, Library]
[Home, Library] -> Back -> [Home]
```

### Search

```text
[Home] -> Search -> [Home, Search]
[Home, Library] -> Search -> [Home, Library, Search]
[Home, Library, Genre, Album] -> Search -> [Home, Library, Search]
[Home, Playlist] -> Search -> [Home, Search]
```

### Details

```text
[Home, Library] -> Genre(5) -> Album(42) -> Artist(7)
Back -> Album(42) -> Back -> Genre(5) -> Back -> Library
```

### Settings

```text
[Home] -> Settings -> AiDebugSettings
[Home, Library] -> Settings -> AiDebugSettings
```

### Playback overlays

```text
[content] -> Player -> Queue
Back -> Player
Back -> content
clear playback -> content
```

## 6. Карта жизненного цикла

При публикации нового navigation state:

1. Вычислить новый state и определить новый `NavigationEntry` без публикации.
2. Для нового detail entry синхронно зарегистрировать descriptor до публикации state.
3. Сравнить старый и новый `currentContentEntry.entryId`.
4. Если entry не изменился, не трогать content subscription.
5. Если entry изменился, отменить предыдущий detail job.
6. Синхронно подготовить UI header/loading state, помеченный новым `entryId`.
7. Опубликовать navigation state и активировать reactive data нового route.
8. Перед каждым async update сверять active `entryId`.
9. При удалении entries очистить их header descriptors и saveable UI state.
10. Search state очищать только если Search entry отсутствует в новом back stack.

Player/Queue меняют верхний destination, но не content entry, поэтому detail job продолжает работать.

Detail UI state должен содержать либо active `entryId`, либо эквивалентный token. Compose не отображает selected detail data, если token не соответствует `currentContentEntry`. Это исключает кадр, в котором новый route уже опубликован, а UI всё ещё показывает данные предыдущего detail.

## 7. Saveable state

Текущий ключ `Screen` недостаточен для нескольких экземпляров одного detail route.

Целевые правила:

- динамические details используют `NavigationEntry.entryId`;
- Home и Library используют стабильные presentation keys, чтобы сохранять scroll state между переключениями;
- после окончательного удаления динамического entry вызывается очистка его saveable state;
- preview Predictive Back не создаёт и не удаляет saveable state;
- очистка происходит только после commit нового стека.

Без явной очистки уникальные entry IDs приведут к накоплению состояний в `SaveableStateHolder`.

## 8. Тестовая карта

### Заменяемые тесты

Navigation-тесты в `ComposeAppCommonTest.kt`, построенные вокруг `Screen`, `AppDestination` и старого `PlayerUiState`, переносятся на новые navigation types.

### Новые группы

```text
AppNavigationStateTest
    root/main page selection
    push and consecutive duplicate suppression
    unique entry IDs
    pop and root protection
    activeMainDestination
    currentContentEntry
    Search anchoring and detail suffix removal
    Settings structural order
    Player/Queue structural order
    playback overlay removal
    Back preview equality with committed pop

PlayerViewModelNavigationTest
    semantic command -> expected stack
    content activation only when content entry changes
    stale detail emission is ignored
    Search cleanup only after Search removal
    playback availability checked outside navigation model
```

Compose acceptance проверяет route rendering, active dock item, saveable key isolation и последовательное закрытие overlays.

## 9. Что не затрагивается

- `PlaybackQueueController` и `PlaybackResolver`;
- `PlaybackTimeSource` и SavePoint timing;
- Android Media3 и Desktop Java Sound;
- Room schema и migrations;
- Gradle dependencies;
- library paging и metadata pipelines;
- playback persistence;
- process-death navigation restoration.

## 10. Удаление после миграции

После полного переключения consumers удаляются:

- `Screen`;
- `AppDestination`;
- `Screen.toAppDestination()`;
- `AppDestination.toScreen()`;
- navigation helpers старого `PlayerUiState`;
- navigation projection внутри `PlaybackUiState`;
- generic `PlayerViewModel.navigateTo(Screen)`;
- логика замены предыдущих Playlist/LibraryCollection destinations;
- прямые изменения navigation state в обход единой publish-функции.

Удаление выполняется только после перевода UI и тестов, не в первом migration step.

## 11. Основные риски карты

| Риск | Контроль |
|---|---|
| Back вернул route, но UI показывает данные следующего detail | activation по `entryId` + descriptor store |
| отменённая корутина поздно записала данные | проверка active entry перед update |
| Player/Queue перезапускают detail subscription | сравнение `currentContentEntry`, а не top entry |
| одинаковые details делят scroll state | уникальный `entryId` |
| `SaveableStateHolder` бесконечно растёт | очистка удалённых dynamic entry IDs |
| Search из Home возвращает Library | Search из Home хранится как `[Home, Search]`, Library только визуальная база renderer |
| navigation снова начинает зависеть от playback | current-track check остаётся в ViewModel |
| добавление route снова требует множества mappings | один `AppRoute`, semantic callback и один renderer case |

## 12. Предварительная оценка области изменений

Обязательные области:

```text
shared/navigation                 новые pure types
shared/model/PlayerViewModel      orchestration и content activation
shared/playback/PlayerUiState     удаление Screen/navigation projection
composeApp/app/App                route rendering и state collection
composeApp/ui/Components          active dock destination
composeApp/ui/LibraryScreen       Settings action
composeApp/ui/PlayerOverlayHost   navigation-derived visibility
shared/commonTest                 navigation tests
```

Условные области:

```text
LibrarySearchScreen               только если потребуется композиционно отделить Library base
Library collection UI models      descriptor type для entry header
MusicLibrarySource/Repository     не требуется при descriptor store
PlatformBackHandler               не меняется до Predictive Back этапа
```
