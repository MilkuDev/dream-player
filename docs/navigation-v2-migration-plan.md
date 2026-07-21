# DreamPlayer Navigation v2 — план миграции

Статус: этапы 0–7 выполнены 2026-07-21. Автоматические build/test gates успешны; интерактивные UI-сценарии остаются ручной acceptance-проверкой.

Связанные документы:

- `docs/navigation-v2-spec.md`
- `docs/navigation-v2-change-map.md`

## Принцип миграции

Каждый этап должен завершаться компилируемым и проверяемым проектом. Старые типы удаляются только после перевода всех production consumers и тестов. Одновременно существует только один изменяемый источник navigation state; параллельное dual-write состояние запрещено.

Основные build gates:

```powershell
.\gradlew.bat :shared:compileKotlinJvm
.\gradlew.bat :shared:jvmTest
.\gradlew.bat :composeApp:compileKotlinJvm
```

Финальные platform gates:

```powershell
.\gradlew.bat :androidApp:assembleDebug
.\gradlew.bat :desktopApp:createDistributable
```

Изменения Gradle dependencies, Room schema и platform playback в миграцию не входят.

## Этап 0. Baseline

### Цель

Зафиксировать рабочую точку до изменения navigation model.

### Действия

- выполнить JVM compilation shared и composeApp;
- выполнить shared JVM tests;
- зафиксировать существующие navigation tests и известное текущее поведение;
- проверить отсутствие непреднамеренных изменений в рабочем дереве;
- не исправлять посторонние ошибки в рамках navigation migration.

### Exit criteria

- baseline команды успешны либо известные внешние блокеры документированы;
- область navigation changes отделена от существующих пользовательских изменений.

## Этап 1. Pure navigation core

### Цель

Добавить целевую navigation model без подключения к production UI и ViewModel.

### Новые файлы

```text
shared/src/commonMain/kotlin/org/milkdev/dreamplayer/navigation/AppRoute.kt
shared/src/commonMain/kotlin/org/milkdev/dreamplayer/navigation/NavigationEntry.kt
shared/src/commonMain/kotlin/org/milkdev/dreamplayer/navigation/AppNavigationState.kt
shared/src/commonTest/kotlin/org/milkdev/dreamplayer/navigation/AppNavigationStateTest.kt
```

### Реализуемый контракт

- route types из Navigation v2 specification;
- immutable `NavigationEntry(entryId, route)`;
- immutable stack с Home первым entry;
- уникальная выдача dynamic entry IDs;
- стабильная identity основных Home/Library presentation entries;
- `currentDestination`;
- `currentContentEntry`;
- `activeMainDestination`;
- `canNavigateBack`;
- `selectMainPage`;
- `push` с подавлением последовательного дубля;
- `pop`;
- Search anchoring и удаление detail suffix;
- удаление playback overlays;
- структурная проверка Settings -> AiDebugSettings и Player -> Queue;
- получение Back preview без публикации.

### Что не меняется

- `PlayerViewModel` продолжает использовать старый internal `AppNavigationState`;
- Compose продолжает использовать `Screen`;
- пользовательское поведение не меняется.

### Tests

- все чистые stack operations;
- route equality и unique entry IDs;
- повторный exact top route;
- одинаковые routes в разных entries;
- Search из Home и Library;
- Search из detail suffix;
- current content и active main destination с Player/Queue;
- preview Back равен результату будущего commit, но не изменяет исходный state.

### Exit criteria

- новые типы не имеют Compose/platform/repository imports;
- shared compilation и JVM tests успешны;
- production navigation не затронута.

## Этап 2. Новый core как внутренний источник истины ViewModel

### Цель

Перевести `PlayerViewModel` на новый `AppNavigationState`, сохранив старый UI contract через временную read-only проекцию.

### Изменения

- добавить `_navigationState: MutableStateFlow<AppNavigationState>`;
- открыть `navigationState: StateFlow<AppNavigationState>`;
- удалить старый mutable `navigationState` и старые navigation types из конца `PlayerViewModel.kt`;
- ввести единую `publishNavigationState(nextState)`;
- через неё выполнять Back, Home, Library, Search, Settings, Player, Queue и очистку playback;
- сохранить временное преобразование `AppRoute -> Screen` только для существующего `PlaybackUiState.currentScreen`;
- сохранить `canNavigateBack`, `playerPresentation` и `isQueueSheetVisible` как временную UI-проекцию;
- оставить временный `navigateTo(Screen)` только как compatibility adapter для ещё не переведённого Compose UI;
- current-track check для Player оставить в ViewModel;
- не передавать playback state в navigation core.

### Временное ограничение

До появления entry-aware content activation detail-переходы сохраняют parity с текущим поведением: новый detail заменяет предыдущий detail suffix. Core уже умеет полную историю, но ViewModel пока не включает её для production detail-команд.

Это предотвращает ситуацию, когда Back возвращает route, данные которого ViewModel ещё не умеет восстановить.

### Особое внимание

- AudioPlayer collector не должен изменять navigation state в обход publish-функции;
- `clearQueue()` удаляет overlays через новый core;
- временная проекция не становится вторым источником истины: в неё нельзя писать напрямую.

### Tests

- существующие navigation tests адаптируются к новому core либо остаются до cleanup;
- ViewModel semantic commands проверяются через публичный navigation StateFlow там, где это возможно без изменения глобальных зависимостей;
- playback overlays удаляются централизованно.

### Exit criteria

- единственный navigation source of truth — новый StateFlow;
- старый Compose UI работает без визуальных изменений;
- shared и composeApp compilation, shared tests успешны.

## Этап 3. Entry-aware content activation

### Цель

Научить ViewModel восстанавливать правильные данные для любого content entry после push и Back.

### Изменения

- ввести лёгкий detail descriptor, keyed by `entryId`;
- descriptor хранит collection/playlist identity и неизменяемый header, но не tracks/albums;
- semantic open-команда вычисляет новый state и итоговый entry без публикации, затем регистрирует descriptor и только после этого публикует state;
- заменить `playlistTracksJob` на название, отражающее все detail routes, либо сохранить Job с документированной общей ролью;
- при publish сравнивать старый и новый `currentContentEntry.entryId`;
- если content entry не изменился, не перезапускать data subscription;
- если изменился, отменить предыдущую detail subscription и активировать новый route;
- selected detail UI state помечать active `entryId`/token и не показывать данные с несовпадающим token;
- при async update сверять active entry ID;
- при Back повторно активировать descriptor предыдущего entry;
- при окончательном удалении entries очищать их descriptors;
- Search state очищать только когда Search entry исчез из нового stack.

### Включаемое поведение

После завершения content activation detail-команды переключаются с compatibility replacement на настоящий push:

```text
Genre(5) -> Album(42) -> Artist(7)
```

Back восстанавливает header и reactive data предыдущего entry.

### Player/Queue rule

Player и Queue меняют top entry, но не `currentContentEntry`. Открытие и закрытие overlays не отменяет detail subscription.

### Tests

- push detail A -> detail B -> Back активирует A;
- одинаковый route в двух entries получает независимые descriptors;
- поздний результат отменённой подписки не меняет active detail;
- Player/Queue не перезапускают detail data;
- удаление stack suffix очищает descriptors;
- Search -> detail -> Back сохраняет query;
- удаление Search очищает query.

### Exit criteria

- полная content history работает на уровне ViewModel state;
- данные после Back соответствуют активному entry;
- UI всё ещё может использовать временный `Screen`, но функциональный stack уже целевой.

## Этап 4A. Compose rendering по NavigationEntry

### Цель

Перевести content rendering и системный Back с legacy Screen projection на navigation StateFlow.

### Изменения в `App.kt`

- collect navigation StateFlow;
- `PlatformBackHandler` читает `navigationState.canNavigateBack`;
- `AnimatedContent` использует `currentContentEntry`;
- `when` рендерит `AppRoute`;
- `SaveableStateProvider` использует `entryId` для dynamic routes;
- Home и Library получают стабильные presentation keys;
- Player/Queue исчезают из content `when`;
- Settings и AI Debug используют semantic ViewModel commands.

### Saveable cleanup

- Compose отслеживает IDs, удалённые из committed stack;
- dynamic state удаляется из `SaveableStateHolder` только после commit;
- Home/Library state сохраняется между основными переключениями;
- Back preview не создаёт и не удаляет saveable state.

### Временная совместимость

- BottomDock и PlayerOverlayHost могут ещё читать legacy projection из `PlaybackUiState`;
- `Screen` пока не удаляется.

### Tests / verification

- два одинаковых route с разными entry IDs не делят UI state;
- Back рендерит предыдущий entry;
- Settings возвращается к Home или Library;
- system Back использует новый state.

### Exit criteria

- основной content rendering не зависит от `Screen`;
- приложение компилируется с оставшимися временными adapters.

## Этап 4B. Dock, Search и playback overlays

### Цель

Перевести оставшиеся Compose consumers на navigation-derived state.

### NavigationDock / BottomDock

- заменить `currentScreen` на `activeMainDestination`;
- заменить generic `onNavigate` на `onHomeClick` и `onLibraryClick`;
- Search получает selected/active состояние;
- SearchDock открывается из semantic `openSearch()`;
- Search из detail удаляет detail suffix и сохраняет Home/Library anchor;
- повторный выбор Home/Library возвращает раздел к базовому stack;
- структура допускает будущий AI main destination без изменения существующих route mappings.

### Search renderer

- визуально отображает библиотечный контент, строку поиска и фильтры;
- не добавляет Library entry при открытии Search из Home;
- Back из `[Home, Search]` возвращает Home;
- Back из `[Home, Library, Search]` возвращает Library.

### Settings

- Home продолжает получать `onSettingsClick`;
- Library получает settings action в top bar;
- оба используют `openSettings()`;
- Settings subpages используют semantic push.

### PlayerOverlayHost

- visibility Player выводится из наличия Player/Queue route и current track;
- visibility Queue выводится из top Queue route;
- legacy `playerPresentation`/`isQueueSheetVisible` больше не читаются;
- dismiss выполняет Back;
- clear playback удаляет overlay suffix.

### Exit criteria

- ни один Compose consumer не зависит от `Screen`;
- dock показывает Home/Library/Search согласно `activeMainDestination`;
- Search, Settings, Player и Queue проходят acceptance scenarios;
- composeApp compilation успешна.

## Этап 5. Удаление legacy navigation

### Цель

Удалить временные adapters и дублированное состояние после полного перевода consumers.

### Удаляется

- `Screen`;
- `PlayerViewModel.navigateTo(Screen)`;
- временная `AppRoute -> Screen` проекция;
- `PlaybackUiState.currentScreen`;
- `PlaybackUiState.canNavigateBack`;
- `PlaybackUiState.playerPresentation`;
- `PlaybackUiState.isQueueSheetVisible`;
- navigation helpers старого `PlayerUiState`;
- legacy navigation tests в `ComposeAppCommonTest.kt`;
- прямые navigation updates вне publish-функции.

### Проверки

- repository-wide search не находит production references на `Screen` и старые helpers;
- navigation package остаётся единственным местом stack rules;
- ViewModel остаётся владельцем live state, но не реализует pure transition logic;
- старый `PlayerUiState` оценивается отдельно: удалить полностью, если после очистки он нужен только устаревшим тестам.

### Exit criteria

- отсутствуют параллельные navigation models;
- shared compilation/tests и composeApp compilation успешны.

## Этап 6. Полная тестовая и platform verification

### Shared

- все pure navigation tests;
- content activation tests;
- lifecycle/stale-result tests;
- Search cleanup tests;
- overlay structural tests;
- Back preview tests.

### Compose acceptance

Ручная или доступная UI-проверка:

```text
Home -> Search -> Back -> Home
Library -> Search -> Back -> Library
Library -> Genre -> Album -> Back -> Genre
Search -> Artist -> Playlist -> Back -> Artist -> Back -> Search
Home -> Settings -> AI Debug -> Back -> Settings -> Back -> Home
Library -> Settings -> Back -> Library
Library -> Player -> Queue -> Back -> Player -> Back -> Library
detail chain -> Search -> Back -> исходная main page
одинаковый route в двух entries -> независимый saveable state
```

### Platform gates

- Android debug APK;
- Desktop distributable;
- Android system Back;
- Desktop отсутствие platform Back regression;
- Player/Queue gestures;
- отсутствие изменений playback timing.

### Exit criteria

- все обязательные сценарии подтверждены;
- нет известных navigation regressions;
- новая модель готова к отдельному позднему Predictive Back UI этапу.

## Этап 7. Документация и readiness closure

### После успешной реализации

- обновить `architecture-snapshot.md` по фактическому состоянию;
- заменить устаревшее описание navigation package и UI state;
- указать `NavigationEntry`, `activeMainDestination` и content activation;
- отметить Predictive Back animation как следующий отдельный этап;
- при необходимости обновить `AGENTS.md`, только если изменились архитектурные правила;
- сохранить change map и specification как decision history либо отметить их статус как implemented.

### Финальный gate

Рефакторинг считается завершённым, когда документация описывает фактический код, а не целевое состояние, и после cleanup отсутствует compatibility navigation layer.

## Commit / review boundaries

Рекомендуемые независимые review units:

```text
1. pure navigation core + tests
2. ViewModel source-of-truth migration + compatibility projection
3. entry-aware content activation + detail history
4. Compose entry rendering + saveable state
5. dock/search/settings/overlay migration
6. legacy cleanup + test migration
7. documentation update
```

Каждый unit должен иметь собственный успешный build gate. Не объединять pure core, UI migration и cleanup в один большой непроверяемый change set.

## Rollback strategy

- до этапа 2 новый core не влияет на production;
- на этапе 2 UI продолжает работать через compatibility projection;
- до этапа 4 legacy UI contract сохраняется;
- cleanup выполняется только после подтверждения всех consumers;
- при regression откатывается последний migration unit, а не переписывается весь navigation core;
- descriptor store и navigation core не требуют изменений Room или persisted data, поэтому rollback не нуждается в миграции пользовательских данных.
