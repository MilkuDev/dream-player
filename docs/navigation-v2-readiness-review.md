# DreamPlayer Navigation v2 — readiness review

Дата review: 2026-07-21.

Вердикт: **GO — архитектурный пакет готов к поэтапной реализации.**

Итог реализации: Navigation v2 внедрена 2026-07-21; legacy navigation удалена, автоматические shared/Compose/Android/Desktop gates успешны.

## 1. Проверенные материалы

- `docs/navigation-v2-spec.md` — целевой контракт и UX-семантика;
- `docs/navigation-v2-change-map.md` — фактическая область изменений;
- `docs/navigation-v2-migration-plan.md` — buildable migration stages;
- `docs/navigation-v2-test-matrix.md` — automated, acceptance и platform gates;
- текущие `PlayerViewModel`, `PlaybackUiState`, Compose routing, dock, Search, Settings, Player/Queue и common tests.

Документы описывают одну и ту же модель и не требуют изменения Gradle dependencies, Room schema или playback architecture.

## 2. Зафиксированные решения

- Home — первый и корневой entry приложения.
- Library — основная страница; Back возвращает Home.
- повторный выбор Home/Library сбрасывает соответствующий раздел к базе;
- Search — самостоятельный main destination с визуальным Library content;
- Search из Home хранится как `[Home, Search]`;
- Search из Library хранится как `[Home, Library, Search]`;
- Search из detail удаляет detail suffix и сохраняет Home/Library anchor;
- Playlist и LibraryCollection routes содержат identity arguments;
- stack хранит `NavigationEntry(entryId, route)`;
- одинаковые routes разрешены в разных entries, consecutive top duplicate подавляется;
- context links задаются semantic callbacks, полного navigation graph нет;
- navigation core проверяет только структурные инварианты;
- Settings доступен из Home и Library;
- Player/Queue образуют последовательный overlay suffix;
- process-death restoration не входит в задачу;
- Predictive Back UI отложен, но preview/cancel/commit contract закладывается сейчас.

## 3. Проверка архитектурных границ

### Navigation model

Владеет:

- route stack;
- entry identity;
- pure transitions;
- structural invariants;
- derived current/content/main destinations;
- Back preview.

Не владеет:

- playback availability;
- coroutine lifecycle;
- repository access;
- screen data;
- Compose rendering;
- platform Back APIs.

### PlayerViewModel

Владеет:

- live navigation StateFlow;
- semantic application commands;
- проверкой current track;
- content activation;
- descriptor lifecycle;
- committed state publication;
- cleanup Search/details/playback overlays.

### UI

Владеет:

- route rendering;
- active dock presentation;
- Search visual composition;
- Player/Queue layering;
- saveable UI state;
- platform gesture progress в будущем.

Границы соответствуют существующим инвариантам проекта.

## 4. Критические риски и закрывающие требования

| Риск | Статус | Обязательный контроль |
|---|---|---|
| Route восстановлен, данные остались от следующего detail | закрыт планом | descriptor и UI token keyed by `entryId` |
| Descriptor отсутствует в момент публикации route | закрыт планом | descriptor регистрируется до commit state |
| Отменённый Flow поздно пишет данные | закрыт планом | active-entry check перед каждым async update |
| Player/Queue перезапускают content subscription | закрыт планом | сравнивается `currentContentEntry`, не top entry |
| Два одинаковых route делят scroll state | закрыт планом | unique entry ID как dynamic saveable key |
| SaveableStateHolder накапливает удалённые IDs | закрыт планом | cleanup removed committed entries |
| Search из Home возвращает Library | закрыт моделью | `[Home, Search]`; Library является визуальной базой renderer |
| Current-track check попадает в navigation core | запрещено | проверка остаётся во ViewModel |
| Queue создаётся без Player | закрыт structural invariant | invalid transition возвращает неизменный state |
| openPlayer поверх Queue повреждает suffix | закрыт контрактом | существующий Player -> Queue suffix не меняется |
| Preview запускает lifecycle side effects | запрещено | preview не публикуется и не активирует данные |
| Параллельные navigation sources расходятся | запрещено | один StateFlow; legacy projection только read-only adapter |

Незакрытых критических архитектурных рисков не обнаружено.

## 5. Допустимые временные состояния миграции

- новый pure core существует рядом со старым, но не управляет production;
- после перевода ViewModel старый Compose временно читает read-only Screen projection;
- detail history включается только после entry-aware content activation;
- Compose consumers переводятся до удаления legacy types;
- cleanup выполняется только после repository-wide consumer check.

Недопустимо:

- одновременно изменять старый и новый back stack;
- включать nested detail history до восстановления entry data;
- удалять Screen до перевода всех Compose consumers;
- обновлять architecture snapshot как будто migration завершена раньше фактического cleanup.

## 6. Test readiness

Готовы обязательные наборы:

- pure stack operations;
- entry identity;
- Search anchoring;
- details и Settings;
- Player/Queue grammar;
- Back preview;
- semantic command checks;
- entry-aware data activation;
- stale-result rejection;
- Search lifecycle;
- Compose acceptance;
- Android/Desktop build gates;
- regression и static cleanup.

Ограничение принято: Compose UI automation не добавляется без отдельного разрешения на dependencies. Mandatory UI cases проверяются acceptance-процедурой.

## 7. Compatibility assessment

### Поведение, которое намеренно изменится

- details больше не заменяют друг друга, а формируют историю;
- Back восстанавливает предыдущий Playlist/Album/Artist/Genre;
- Search получает собственный active main state;
- Search из detail возвращает main anchor, а не detail;
- Settings становится доступен также из Library;
- navigation UI state перестаёт дублироваться внутри PlaybackUiState.

### Поведение, которое должно сохраниться

- Home — стартовый экран;
- Library -> Back -> Home;
- Player -> Queue -> последовательный Back;
- запрет playback overlays без playable context на orchestration уровне;
- library paging, playback queue, timing, save points и settings flows;
- Android-only Back adapter и Desktop isolation.

## 8. Scope control

В implementation не включать:

- playback persistence bug fixes;
- paging/loading refactoring;
- repository redesign;
- DI;
- новые navigation/Compose dependencies;
- AI main page;
- process-death stack restoration;
- интерактивную Android Predictive Back анимацию;
- несвязанный cleanup большого PlayerViewModel.

Если во время migration обнаруживается проблема вне этого списка, она документируется отдельно и не расширяет navigation change автоматически.

## 9. Start gate

Реализацию можно начинать при соблюдении порядка:

1. Выполнить baseline build/tests.
2. Реализовать pure navigation core и его tests без production wiring.
3. Провести review Stage 1 перед подключением core к ViewModel.
4. Продолжать только по migration stages с отдельным build gate после каждого.

Первый implementation change set ограничен новыми файлами `shared/navigation` и pure common tests. Он не должен менять `PlayerViewModel`, Compose UI или пользовательское поведение.

## 10. Финальный вывод

Navigation v2 достаточно определена по ответственности, UX, data flow, lifecycle, миграции и тестам. Открытых решений, способных изменить основу реализации, не осталось.

Разрешён следующий этап: **Stage 0 baseline, затем Stage 1 pure navigation core + common tests.**
