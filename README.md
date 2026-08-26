# PluginEDT (fork) — помещение в хранилище 1С из EDT

Форк плагина **[ZigRinat85/PluginEDT](https://github.com/ZigRinat85/PluginEDT)** — плагин **storage** (`dev.zigr.dt.team.ui.storage`): команда «Поместить в хранилище» из EDT в хранилище конфигурации 1С.

Это **не** pack/сборка дистрибутива — только доработки сценария помещения.

| | |
|---|---|
| **Upstream** | https://github.com/ZigRinat85/PluginEDT |
| **Этот fork** | https://github.com/SergeiPereskokov/PluginEDT |
| **Рабочая ветка доработок** | `fix/storage-feedback` |
| **База** | upstream v0.4.0 |

---

## Зачем форк

Upstream 0.4.0 на типовом layout «один EDT-проект = один git» и на EDT **2026.2** ломается или обрывает сценарий.

| Боль | Что происходило | Решение в форке |
|------|-----------------|-----------------|
| Single-project git | Diff с путями `src/...` игнорировался (ждали только `ИмяПроекта/src/...`) → 0 объектов | Layout A + B, `resolveProjectName`, `toWorkspacePath` |
| EDT 2026.2 API | После LoadCfg: `NoSuchMethodError` на `setActualConfigDumpInfo(Path)` | `loadActualConfigDumpInfo(Path)` |
| Залипший sync ИБ | При ошибке после `startUpdateProjectFlow` flow без `finish()`/`cancel()` | `try/finally` + `cancel()` |
| «Поместили» только в ИБ | Захват + LoadCfg без UpdateDBCfg и без commit в хранилище | `/UpdateDBCfg` + `/ConfigurationRepositoryCommit` |
| UI freeze | Долгий цикл на UI-thread, диалоги с worker без `syncExec` | `ProgressMonitorDialog` + `subTask` + `syncExec` |

---

## Изменения форка (полный список)

### 1. Поддержка single-project git (`src/...`)

**Зачем:** типичный layout KICB/EDT — `.git` внутри проекта, пути в diff = `src/Catalogs/...`. Upstream понимал только monorepo `ProjectName/src/...`.

**Что:** в `ExportHandler.getBranchDiff` — layout A (`Project/src/...`) и layout B (`src/...` + имя проекта по work tree). `toWorkspacePath` для FQN-конвертера EDT.

### 2. EDT 2026.2: `loadActualConfigDumpInfo`

**Зачем:** иначе падение после успешного LoadCfg, ConfigDumpInfo не актуализируется.

**Что:** в `Designer.loadConfigurationFromXml` — `IUpdateProjectFlow.loadActualConfigDumpInfo(Path)` вместо удалённого `setActualConfigDumpInfo`.

### 3. `try/finally` + `cancel()` sync flow

**Зачем:** без `finish()` после `startUpdateProjectFlow` синхронизация ИБ в EDT «залипает»; при исключении на `loadActual…` нужен `cancel()`.

**Что:** флаг `finished`; в `finally` при `!finished` — `updateProjectFlow.cancel()` (best-effort). В `ExportHandler.pushBranchDiff` — `designer.dispose()` в `finally`.

### 4. UpdateDBCfg + commit в хранилище

**Зачем:** после LoadCfg Main ≠ DB; без UpdateDBCfg и `/ConfigurationRepositoryCommit` объекты остаются только в основной конфигурации ИБ, в хранилище 1С не попадают.

**Что:** `Designer.updateDatabaseConfiguration` (`/UpdateDBCfg`), `Designer.storeObjects` (`/ConfigurationRepositoryCommit`, комментарий `PluginEDT: {ветка}`).

### 5. Прогресс и UI с worker-thread

**Зачем:** иначе EDT «висит» на `Process.waitFor` / JGit / export; MessageBox с non-UI thread → SWTException.

**Что:** `ProgressMonitorDialog.run(true, false, …)` (cancelable=false — Designer `waitFor` без destroy), `subTask` по шагам, реальный monitor у `IExportOperation.run`, UI-диалоги через `Display.syncExec`.

---

## Ветка

- Разработка и история патчей: **`fix/storage-feedback`**
- На GitHub default branch форка после merge содержит те же изменения (см. главную README)

Сравнить с upstream/master:  
https://github.com/SergeiPereskokov/PluginEDT/compare/master...fix/storage-feedback

---

## Краткая установка

1. Собрать/взять `dev.zigr.dt.team.ui.storage` из этого репозитория (ветка `fix/storage-feedback` или актуальный `master` форка).
2. Установить плагин в EDT (dropins / update site — как для upstream PluginEDT).
3. В EDT: настройки хранилища на проекте → команда «Поместить в хранилище» на дескрипторе ветки git↔ИБ.
4. После смены class/плагина — рестарт EDT с `-clean` при странном поведении sync.

Готовая сборка upstream (для сравнения, без патчей форка):  
https://github.com/ZigRinat85/PluginEDT/releases/download/v0.4.0/build.zip

Статья автора upstream:  
https://infostart.ru/1c/articles/2442956/

---

## Новое в версии (upstream, без изменений смысла)

### 0.4.0
- Поддержка EDT 2025.2 (ранние версии EDT не поддерживаются)
- Мелкие исправления

### 0.3.0
- Конфигурация и расширения в одном репозитории
- Настройки в разрезе «Проект»
- Команда настроек в контекстном меню проекта (панель «Разработка» / «Навигатор»)

### 0.2.0
- Расширения конфигураций
- Исправление доп. индексов EDT→ИБ
- Настройки «ИБ + Проект»; опция «Помещать даже если конфигурации различаются»

---

## Open source / лицензия

Репозиторий **публичный**, исходники открыты. Это fork upstream [ZigRinat85/PluginEDT](https://github.com/ZigRinat85/PluginEDT); условия распространения — как у upstream (отдельный файл `LICENSE` в корне пока отсутствует — ориентируйтесь на upstream и практику автора).

---

## Исходники в git

В репозитории — исходники плагина. Сборки stubs/out/class в `.p2` в git не входят.
