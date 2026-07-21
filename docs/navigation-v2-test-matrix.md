# DreamPlayer Navigation v2 — тестовая матрица

Статус: pure navigation/detail lifecycle tests и platform build gates реализованы и успешны 2026-07-21. Compose gestures, Android system Back и визуальные переходы требуют ручной проверки на запущенных приложениях.

Связанные документы:

- `docs/navigation-v2-spec.md`
- `docs/navigation-v2-change-map.md`
- `docs/navigation-v2-migration-plan.md`

## 1. Стратегия проверки

Используются четыре уровня:

1. **Pure common tests** — `AppRoute`, `NavigationEntry`, `AppNavigationState` и stack operations.
2. **Orchestration tests** — решения `PlayerViewModel` о публикации state, активации content entry и cleanup.
3. **Compose acceptance** — rendering, dock selection, saveable state и overlays.
4. **Platform/build verification** — Android/Desktop compilation и platform Back.

Новые test dependencies не добавляются. Автоматические shared-тесты используют существующие `kotlin.test` и `runTest`.

## 2. Ограничения текущей testability

Полная инициализация `PlayerViewModel` запускает application-lifetime coroutines и обращается к глобальным platform/repository objects. Pure navigation tests не должны создавать настоящий `PlayerViewModel`.

Для автоматической проверки orchestration допускается небольшой test seam без DI framework:

- pure-функция, вычисляющая план смены content entry;
- internal функция проверки active `entryId` перед state update;
- узкий internal controller с переданными fake callbacks, если lifecycle нельзя проверить чистыми функциями.

Не требуется:

- DI framework;
- Android ViewModel;
- замена глобальной архитектуры приложения ради тестов;
- real Room, network или AudioPlayer в navigation unit tests.

Если lifecycle-проверка потребует непропорционально большой перестройки, соответствующий сценарий остаётся обязательным acceptance test, а pure invariant покрывается отдельно.

## 3. Test fixtures

Базовые routes:

```text
Home
Library
Search
Settings
AiDebugSettings
Player
Queue
Playlist(10)
Playlist(20)
Genre(5)
Album(42)
Artist(7)
```

Во всех тестах проверяются одновременно:

- порядок routes;
- `entryId` там, где важна identity;
- `currentDestination`;
- `currentContentEntry`;
- `activeMainDestination`;
- `canNavigateBack`.

## 4. Pure navigation core

### 4.1 Root и основные страницы

| ID | Сценарий | Действие | Ожидаемый результат |
|---|---|---|---|
| NAV-ROOT-001 | Начальное состояние | создать state | `[Home]`, Back недоступен |
| NAV-ROOT-002 | Выбор Library | `[Home] -> selectMainPage(Library)` | `[Home, Library]`, active main = Library |
| NAV-ROOT-003 | Back из Library | `[Home, Library] -> pop` | `[Home]`, active main = Home |
| NAV-ROOT-004 | Back из Home | `[Home] -> pop` | переход отклонён, исходный state не меняется |
| NAV-ROOT-005 | Повторный Library в detail | `[Home, Library, Genre(5), Album(42)] -> selectMainPage(Library)` | `[Home, Library]` |
| NAV-ROOT-006 | Home из Library detail | `[Home, Library, Genre(5)] -> selectMainPage(Home)` | `[Home]` |
| NAV-ROOT-007 | Main selection удаляет overlays | `[Home, Library, Player, Queue] -> selectMainPage(Home)` | `[Home]` |
| NAV-ROOT-008 | Home всегда первый | выполнить допустимые операции | первый route остаётся Home |

### 4.2 NavigationEntry identity

| ID | Сценарий | Ожидаемый результат |
|---|---|---|
| NAV-ENTRY-001 | Push нового route | создаётся новый dynamic `entryId` |
| NAV-ENTRY-002 | Последовательный exact duplicate | state и top `entryId` не меняются |
| NAV-ENTRY-003 | `Artist(7) -> Album(42) -> Artist(7)` | два Artist entries имеют разные IDs |
| NAV-ENTRY-004 | Разные IDs одного типа | `Playlist(10)` и `Playlist(20)` создают разные entries |
| NAV-ENTRY-005 | Pop | возвращается существующий предыдущий entry с прежним ID |
| NAV-ENTRY-006 | Select main page | Home/Library используют согласованную стабильную presentation identity |
| NAV-ENTRY-007 | Preview Back | не выдаёт новый entry ID и не изменяет счётчик committed state |

### 4.3 Search

| ID | Исходный stack | Действие | Ожидаемый stack |
|---|---|---|---|
| NAV-SEARCH-001 | `[Home]` | open Search | `[Home, Search]` |
| NAV-SEARCH-002 | `[Home, Library]` | open Search | `[Home, Library, Search]` |
| NAV-SEARCH-003 | `[Home, Playlist(10)]` | open Search | `[Home, Search]` |
| NAV-SEARCH-004 | `[Home, Library, Genre(5), Album(42)]` | open Search | `[Home, Library, Search]` |
| NAV-SEARCH-005 | `[Home, Search]` | Back | `[Home]` |
| NAV-SEARCH-006 | `[Home, Library, Search]` | Back | `[Home, Library]` |
| NAV-SEARCH-007 | `[Home, Search, Artist(7)]` | вычислить active main | Search |
| NAV-SEARCH-008 | `[Home, Library, Search, Playlist(10)]` | Back дважды | сначала Search, затем Library |
| NAV-SEARCH-009 | Search уже top | open Search | дубль не добавляется |

### 4.4 Detail history

| ID | Сценарий | Ожидаемый результат |
|---|---|---|
| NAV-DETAIL-001 | Library -> Genre -> Album | stack сохраняет оба detail entry |
| NAV-DETAIL-002 | Back из Album | активируется существующий Genre entry |
| NAV-DETAIL-003 | Search -> Artist -> Playlist | каждый route остаётся в истории |
| NAV-DETAIL-004 | Повторяющийся Artist через Album | одинаковые routes сохраняются как разные entries |
| NAV-DETAIL-005 | Detail из Home | active main остаётся Home |
| NAV-DETAIL-006 | Detail из Library | active main остаётся Library |
| NAV-DETAIL-007 | Detail из Search | active main остаётся Search |
| NAV-DETAIL-008 | Main page reselection | весь detail suffix удаляется |

### 4.5 Settings

| ID | Сценарий | Ожидаемый результат |
|---|---|---|
| NAV-SET-001 | `[Home] -> Settings` | `[Home, Settings]` |
| NAV-SET-002 | `[Home, Library] -> Settings` | `[Home, Library, Settings]` |
| NAV-SET-003 | `Settings -> AiDebugSettings` | подпункт добавлен |
| NAV-SET-004 | AiDebugSettings без Settings predecessor | переход отклонён |
| NAV-SET-005 | Back из AI Debug | возвращает тот же Settings entry |
| NAV-SET-006 | Back из Settings после Library | возвращает Library |
| NAV-SET-007 | Settings не меняет active main | сохраняется Home либо Library |

### 4.6 Player и Queue

Navigation core проверяет структуру. Наличие current track проверяется отдельно orchestration-слоем.

| ID | Сценарий | Ожидаемый результат |
|---|---|---|
| NAV-OVERLAY-001 | Content -> Player | Player добавлен верхним entry |
| NAV-OVERLAY-002 | Player -> Queue | Queue добавлен после Player |
| NAV-OVERLAY-003 | Queue без Player | переход отклонён |
| NAV-OVERLAY-004 | Повторный Player сверху | дубль не добавлен |
| NAV-OVERLAY-005 | Повторный Queue сверху | дубль не добавлен |
| NAV-OVERLAY-006 | Queue -> Back | top = Player |
| NAV-OVERLAY-007 | Player -> Back | top = исходный content |
| NAV-OVERLAY-008 | Overlay не меняет current content | content `entryId` прежний |
| NAV-OVERLAY-009 | Overlay не меняет active main | Home/Library/Search прежний |
| NAV-OVERLAY-010 | removePlaybackOverlays | Player и Queue удалены, content history сохранена |
| NAV-OVERLAY-011 | Playback routes только suffix | недопустимая структура не создаётся public operations |
| NAV-OVERLAY-012 | openPlayer при top Queue | существующий suffix Player -> Queue сохраняется без изменений |

### 4.7 Back preview / Predictive readiness

| ID | Сценарий | Ожидаемый результат |
|---|---|---|
| NAV-PREVIEW-001 | Preview на Home | результата Back нет |
| NAV-PREVIEW-002 | Preview из detail | preview равен обычному pop result |
| NAV-PREVIEW-003 | Preview из Queue | preview показывает Player |
| NAV-PREVIEW-004 | Preview из Player | preview показывает content |
| NAV-PREVIEW-005 | Preview не мутирует исходный state | исходный stack и IDs прежние |
| NAV-PREVIEW-006 | Cancel | committed state остаётся исходным |
| NAV-PREVIEW-007 | Commit | публикуется state, эквивалентный обычному pop |
| NAV-PREVIEW-008 | Preview не запускает allocation | следующий committed ID не пропущен |

## 5. Orchestration и content activation

### 5.1 Semantic commands

| ID | Проверка | Ожидаемый результат |
|---|---|---|
| VM-NAV-001 | openPlayer без current track | navigation state не меняется |
| VM-NAV-002 | openPlayer с current track | добавляется Player |
| VM-NAV-003 | openQueue без top Player | команда не создаёт Queue |
| VM-NAV-004 | openSettings из Home | добавляется Settings |
| VM-NAV-005 | openSettings из Library | источник Library сохраняется |
| VM-NAV-006 | semantic open detail | route содержит правильные type и entity ID |
| VM-NAV-007 | generic `navigateTo(Screen)` после cleanup | API отсутствует |

### 5.2 Content lifecycle

| ID | Сценарий | Ожидаемый результат |
|---|---|---|
| VM-ACT-001 | Content entry не изменился | subscription не перезапускается |
| VM-ACT-002 | Genre -> Album | Genre job отменён, Album job запущен |
| VM-ACT-003 | Album -> Back -> Genre | Genre descriptor и subscription восстановлены |
| VM-ACT-004 | Album -> Player -> Queue | Album job остаётся активным |
| VM-ACT-005 | Быстрый Genre -> Album -> Artist | поздний Genre/Album result игнорируется |
| VM-ACT-006 | Удаление dynamic entry | descriptor удалён |
| VM-ACT-007 | Exact top duplicate | descriptor и job не создаются повторно |
| VM-ACT-008 | Два одинаковых route entries | descriptors разделены по entry ID |
| VM-ACT-009 | Main reselection | descriptors удалённого suffix очищены |
| VM-ACT-010 | Публикация нового detail route | UI state уже содержит header/loading token нового entry и не показывает данные старого entry |

### 5.3 Search lifecycle

| ID | Сценарий | Ожидаемый результат |
|---|---|---|
| VM-SEARCH-001 | Search -> Artist | query/results сохраняются |
| VM-SEARCH-002 | Artist -> Back -> Search | прежний query доступен |
| VM-SEARCH-003 | Search -> Back | Search state очищен после commit |
| VM-SEARCH-004 | Search -> selectMainPage | Search state очищен |
| VM-SEARCH-005 | Back preview из Search | Search state ещё не очищен |
| VM-SEARCH-006 | Predictive cancel | Search state остаётся прежним |

### 5.4 Central publish path

| ID | Проверка | Ожидаемый результат |
|---|---|---|
| VM-PUBLISH-001 | Обычный переход | state меняется через одну publish-функцию |
| VM-PUBLISH-002 | AudioPlayer потерял current track | overlays удалены через publish path |
| VM-PUBLISH-003 | clearQueue | overlays удалены и projection согласована |
| VM-PUBLISH-004 | Back | cleanup выполняется после commit, не до него |
| VM-PUBLISH-005 | Preview | publish и cleanup не вызываются |

## 6. Compose acceptance matrix

UI automation не предполагается без отдельного разрешения на test dependencies. Эти сценарии обязательны для ручной проверки либо реализуются существующими средствами, если они уже доступны.

### 6.1 Rendering

| ID | Сценарий | Ожидаемый результат |
|---|---|---|
| UI-RENDER-001 | Home entry | показан HomeScreen |
| UI-RENDER-002 | Library entry | показан LibraryScreen |
| UI-RENDER-003 | Playlist(10) | показаны данные playlist 10 |
| UI-RENDER-004 | Genre -> Album -> Back | после Back показан Genre, не Album |
| UI-RENDER-005 | два Artist(7) entries | каждый использует собственный saveable key |
| UI-RENDER-006 | popped dynamic entry | его saveable state очищается после commit |
| UI-RENDER-007 | Home/Library switching | scroll state основных страниц сохраняется согласно stable keys |

### 6.2 Dock и Search

| ID | Сценарий | Ожидаемый результат |
|---|---|---|
| UI-DOCK-001 | Home | selected Home |
| UI-DOCK-002 | Library detail | selected Library |
| UI-DOCK-003 | Search из Home | selected Search без промежуточного selected Library |
| UI-DOCK-004 | Search из Library | selected Search |
| UI-DOCK-005 | Detail из Search | active main остаётся Search |
| UI-DOCK-006 | Повторный Library | detail chain закрывается, показан Library root |
| UI-SEARCH-001 | Search из Home | визуально Library search, Back возвращает Home |
| UI-SEARCH-002 | Search из Library | Back возвращает Library |
| UI-SEARCH-003 | Search query -> detail -> Back | query и results сохранены |
| UI-SEARCH-004 | Полное закрытие Search | следующее открытие начинает пустой Search |

### 6.3 Settings

| ID | Сценарий | Ожидаемый результат |
|---|---|---|
| UI-SET-001 | Settings из Home top bar | Back возвращает Home |
| UI-SET-002 | Settings из Library top bar | Back возвращает Library |
| UI-SET-003 | AI Debug | Back последовательно возвращает Settings, затем источник |

### 6.4 Playback overlays

| ID | Сценарий | Ожидаемый результат |
|---|---|---|
| UI-OVERLAY-001 | Library -> Player | Library остаётся под fullscreen Player |
| UI-OVERLAY-002 | Player -> Queue | Queue находится поверх Player |
| UI-OVERLAY-003 | Back Queue | закрыта только Queue |
| UI-OVERLAY-004 | Back Player | показан исходный content |
| UI-OVERLAY-005 | drag Queue dismiss | эквивалент одного Back |
| UI-OVERLAY-006 | drag Player dismiss | эквивалент одного Back |
| UI-OVERLAY-007 | clear queue | оба overlay закрыты, content сохранён |
| UI-OVERLAY-008 | overlay поверх detail | detail scroll/data не перезапущены |

## 7. Platform verification

| ID | Platform | Проверка |
|---|---|---|
| PLATFORM-001 | JVM shared | `:shared:compileKotlinJvm` |
| PLATFORM-002 | JVM shared tests | `:shared:jvmTest` |
| PLATFORM-003 | Compose JVM | `:composeApp:compileKotlinJvm` |
| PLATFORM-004 | Android | `:androidApp:assembleDebug` |
| PLATFORM-005 | Desktop | `:desktopApp:createDistributable` |
| PLATFORM-006 | Android | системный Back выполняет ровно один pop |
| PLATFORM-007 | Android | Back disabled на `[Home]` |
| PLATFORM-008 | Desktop | отсутствие Android API в shared/navigation |
| PLATFORM-009 | Desktop | существующие UI-кнопки Back работают через тот же state |

Apple compilation остаётся дополнительной compile-only проверкой и не означает готовность Apple runtime-заглушек.

## 8. Regression matrix

Навигационный рефакторинг не должен менять:

| ID | Область | Проверка |
|---|---|---|
| REG-001 | Playback queue | play/next/previous/shuffle/repeat работают как до изменения |
| REG-002 | Playback timing | UI продолжает читать `PlaybackTimeSource` напрямую |
| REG-003 | Save points | pause/track change/worker persistence не нарушены |
| REG-004 | Library paging | load next/reset не зависит от navigation entries |
| REG-005 | Daily playlist | Home и daily playlist открываются корректно |
| REG-006 | Favorites | Player favorite action не зависит от overlay state migration |
| REG-007 | Settings | blur/night/AI/Last.fm StateFlows не изменены |
| REG-008 | Queue display | Queue open по-прежнему вызывает display resolution |
| REG-009 | Track loss | исчезновение current track закрывает playback overlays |
| REG-010 | Process restart | navigation начинает с Home; playback restore остаётся отдельным механизмом |

## 9. Static cleanup checks

После legacy cleanup repository search должен подтвердить:

```text
нет production `Screen`
нет production `AppDestination`
нет `toAppDestination` / `toScreen`
нет generic `navigateTo(Screen)`
нет navigation writes в `PlaybackUiState`
нет stack rules вне shared/navigation
нет Compose/platform imports в shared/navigation
```

Допускаются упоминания старых имён только в historical documentation, если документ явно отмечен как audit/history.

## 10. Stage gates

| Migration stage | Обязательные группы |
|---|---|
| Stage 1: pure core | NAV-ROOT, NAV-ENTRY, NAV-SEARCH, NAV-DETAIL, NAV-SET, NAV-OVERLAY, NAV-PREVIEW |
| Stage 2: ViewModel source | VM-NAV, VM-PUBLISH + существующие regression tests |
| Stage 3: activation | VM-ACT, VM-SEARCH |
| Stage 4A: rendering | UI-RENDER + shared/compose compilation |
| Stage 4B: dock/overlays | UI-DOCK, UI-SEARCH, UI-SET, UI-OVERLAY |
| Stage 5: cleanup | static cleanup checks + все automated tests |
| Stage 6: verification | PLATFORM + REG |

## 11. Readiness exit criteria

Navigation v2 готова к завершению реализации, когда:

- все pure navigation cases автоматизированы и проходят;
- orchestration invariants либо автоматизированы, либо имеют явно отмеченный acceptance fallback;
- каждый mandatory Compose scenario проверен;
- Android и Desktop gates успешны;
- static cleanup не находит параллельную navigation model;
- preview/cancel/commit contracts покрыты pure tests, даже если Android Predictive Back UI ещё не реализован;
- ни один тест не требует новой dependency без отдельного согласования.
