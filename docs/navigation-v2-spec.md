# DreamPlayer Navigation v2

Статус: реализовано 2026-07-21. Визуальная Android Predictive Back-интеграция намеренно оставлена отдельным поздним этапом.

## Цель

Создать единую платформенно-независимую навигацию, которая:

- хранит фактическую историю посещённых экранов;
- поддерживает контекстные переходы без полного централизованного navigation graph;
- позволяет добавлять content routes и overlays без переписывания существующих связей;
- сохраняет последовательное поведение Player и Queue;
- не препятствует будущей интеграции Android Predictive Back;
- не требует восстановления navigation stack после перезапуска процесса.

## Архитектурные инварианты

- `PlayerViewModel` остаётся единственным владельцем живого navigation state.
- Navigation state immutable; переход возвращает новое состояние без побочных эффектов.
- Navigation model не зависит от Compose, Android, Desktop, репозиториев и playback state.
- Route хранит идентичность назначения и navigation arguments, но не загруженные данные экрана.
- UI определяет визуальное представление route.
- Активация данных, корутины и reactive subscriptions остаются orchestration-ответственностью `PlayerViewModel`.
- Первый entry стека всегда представляет `Home`.

## Route model

Вместо изоморфных `Screen` и `AppDestination` используется одна route-модель.

Согласованный начальный набор:

```text
Home
Library
Search

Playlist(playlistId)
LibraryCollection(type = Album | Artist | Genre, collectionId)

Settings
AiDebugSettings

Player
Queue
```

`playlistId`, `collectionId` и `type` являются navigation arguments. Названия, обложки, треки, результаты поиска и loading state в route не хранятся.
Для `type` используется канонический доменный `library.LibraryCollectionType`; отдельный navigation-enum с теми же значениями не вводится.

## Navigation entry

Back stack хранит уникальные входы, а не только routes:

```text
NavigationEntry(
    entryId,
    route,
)
```

`entryId` стабилен на протяжении жизни entry и используется как ключ saveable UI state. Это позволяет хранить одинаковые routes в разных местах истории:

```text
Entry 15: Artist(7)
Entry 16: Album(42)
Entry 17: Artist(7)
```

Если верхний entry уже содержит полностью равный route, последовательный дубль не добавляется.

## Семантические группы

### Основные назначения

- `Home` — стартовый и корневой экран приложения.
- `Library` — основная страница.
- `Search` — отдельный режим главной навигации, визуально отображающий Library с поисковой строкой и фильтрами.
- Будущий AI-раздел сможет быть добавлен как новое основное назначение без изменения существующих routes.

### Detail routes

- `Playlist(id)`.
- `LibraryCollection(Album, id)`.
- `LibraryCollection(Artist, id)`.
- `LibraryCollection(Genre, id)`.

Details наследуют активное основное назначение из истории стека.

### Вспомогательные content routes

- `Settings`.
- `AiDebugSettings`.

### Playback overlays

- `Player`.
- `Queue`.

Playback overlays не заменяют content route и не меняют активный пункт нижней навигации.

## Вычисляемое состояние

Navigation state предоставляет:

- `currentDestination` — route верхнего entry;
- `currentContentDestination` — верхний route без учёта playback overlays;
- `activeMainDestination` — последнее основное назначение в стеке;
- `canNavigateBack` — возможность удалить верхний entry;
- preview результата Back без публикации нового состояния.

Примеры `activeMainDestination`:

```text
[Home]                              -> Home
[Home, Library]                     -> Library
[Home, Search]                      -> Search
[Home, Library, Search]             -> Search
[Home, Library, Search, Artist(7)]  -> Search
[Home, Library, Genre(5)]           -> Library
[Home, Library, Player, Queue]      -> Library
```

## Операции

### Выбор основной страницы

```text
selectMainPage(Home)    -> [Home]
selectMainPage(Library) -> [Home, Library]
```

Повторный выбор Home или Library удаляет Search, details, Settings и playback overlays, возвращая раздел к его базовому состоянию.

Back из Library:

```text
[Home, Library] -> [Home] -> выход из приложения
```

### Открытие Search

Из Home:

```text
[Home] -> [Home, Search]
```

Из Library:

```text
[Home, Library] -> [Home, Library, Search]
```

Search сразу становится активным пунктом нижней навигации. UI Search отображает Library content вместе с поисковой строкой и фильтрами.

При открытии Search из detail-цепочки details удаляются:

```text
[Home, Library, Genre(5), Album(42)]
    -> openSearch()
[Home, Library, Search]
```

Если detail был открыт относительно Home:

```text
[Home, Playlist(10)]
    -> openSearch()
[Home, Search]
```

Query и результаты сохраняются, пока entry Search остаётся в стеке. Переход Search -> detail -> Back восстанавливает прежнее состояние поиска. После полного закрытия Search состояние поиска очищается.

### Открытие details

Details добавляются через push и сохраняют точный путь:

```text
[Home, Library, Genre(5)] + Album(42)
    -> [Home, Library, Genre(5), Album(42)]
```

Одинаковые типы route с разными или повторными идентификаторами разрешены, если они не являются последовательным дублем верхнего route.

### Settings

Settings доступен из Home и Library через semantic callback:

```text
[Home, Settings]
[Home, Library, Settings]
```

Settings subpages добавляются последовательно:

```text
[Home, Library, Settings, AiDebugSettings]
```

`AiDebugSettings` структурно допустим только после `Settings`.

### Player и Queue

Допустимая последовательность:

```text
Content -> Player -> Queue
```

Правила:

- `Player` открывается orchestration-слоем только при наличии current track;
- `Queue` структурно допустим только поверх `Player`;
- `openPlayer()` при уже существующем suffix `Player` или `Player -> Queue` не меняет stack;
- повторное открытие верхнего overlay не создаёт дубль;
- Back закрывает Queue, затем Player, затем content;
- очистка playback удаляет Player и Queue, не изменяя content history.

## Ограничение переходов

Navigation model проверяет только структурные инварианты:

- первый entry — Home;
- Queue следует за Player;
- AiDebugSettings следует за Settings;
- playback overlays образуют верхний suffix стека;
- последовательный дубль верхнего route не добавляется.

Контекстные связи не описываются полным navigation graph. Доступность переходов определяется semantic callbacks конкретного UI:

```text
Album можно открыть из Genre
Playlist можно открыть из Search
Artist можно открыть из Album
Settings можно открыть из Home и Library
```

Для добавления новой связи достаточно передать существующую semantic-команду новому экрану.

## Активация данных

После подтверждённой смены `currentContentDestination` `PlayerViewModel` активирует необходимые данные:

```text
Playlist(10) -> наблюдение за плейлистом 10
Genre(5)     -> наблюдение за жанром 5
Album(42)    -> наблюдение за альбомом 42
Artist(7)    -> наблюдение за артистом 7
```

Предыдущая detail-подписка отменяется. Перед записью результата проверяется, что соответствующий `NavigationEntry` всё ещё активен.

Открытие и закрытие Player или Queue не перезапускает content subscription, потому что `currentContentDestination` не меняется.

## UI state

- Compose рендерит `currentContentDestination`.
- Search renderer отображает Library content с поисковым overlay.
- `PlayerOverlayHost` отображает Player и Queue поверх content.
- Нижняя навигация использует `activeMainDestination`, а не верхний route.
- `SaveableStateProvider` использует `NavigationEntry.entryId`.
- Основные Home и Library должны иметь стабильную saveable identity между переключениями.

## Predictive Back readiness

Интерактивный Predictive Back не входит в первый этап реализации, но архитектура обязана поддерживать его без переработки navigation model:

- результат Back можно вычислить без публикации;
- preview не запускает активацию данных и не отменяет текущие подписки;
- cancel не изменяет navigation state;
- commit публикует заранее вычисленный результат `pop`;
- Android progress-анимация остаётся в `composeApp/androidMain`;
- Desktop использует обычный Back над той же моделью.

## Не входит в задачу

- восстановление navigation stack после process death;
- navigation framework или DI framework;
- Android ViewModel и Jetpack Navigation;
- реализация будущего AI-раздела;
- интерактивная Predictive Back анимация на первом этапе;
- кеширование данных всех неактивных detail entries.

## Обязательные сценарии

```text
Home -> Search -> Back -> Home
Library -> Search -> Back -> Library
Library -> Genre(5) -> Album(42) -> Back -> Genre(5)
Search -> Artist(7) -> Playlist(10) -> Back -> Artist(7) -> Back -> Search
Home -> Settings -> AiDebugSettings -> Back -> Settings -> Back -> Home
Library -> Settings -> Back -> Library
Library -> Player -> Queue -> Back -> Player -> Back -> Library
Library -> Album(42) -> selectMainPage(Home) -> Home
Library -> Genre(5) -> Album(42) -> openSearch() -> Search -> Back -> Library
Artist(7) -> Album(42) -> Artist(7) -> последовательный Back по каждому entry
Predictive Back preview -> cancel оставляет исходный stack
Predictive Back preview -> commit публикует результат обычного pop
```
