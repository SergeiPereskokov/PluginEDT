# PluginEDT (fork) — помещение / получение из хранилища 1С из EDT

Форк плагина **[ZigRinat85/PluginEDT](https://github.com/ZigRinat85/PluginEDT)** — плагин **storage** (`dev.zigr.dt.team.ui.storage`): команды «Поместить в хранилище» / «Получить из хранилища» из EDT.

Это **не** pack/сборка дистрибутива — только доработки сценария работы с хранилищем.

| | |
|---|---|
| **Upstream** | https://github.com/ZigRinat85/PluginEDT |
| **Этот fork** | https://github.com/SergeiPereskokov/PluginEDT |
| **Ветка PR #3 port** | `port/pr3-pull-logging` |
| **База** | upstream v0.4.0 + патчи форка |

План порта: workspace KICB `.cursor/plugin-edt-port-pr3-plan.md`. Upstream draft PR: https://github.com/ZigRinat85/PluginEDT/pull/3

---

## Зачем форк

Upstream 0.4.0 на типовом layout «один EDT-проект = один git» и на EDT **2026.2** ломается или обрывает сценарий; нет команды «Получить из хранилища».

| Боль | Что происходило | Решение в форке |
|------|-----------------|-----------------|
| Single-project git | Diff с путями `src/...` игнорировался (ждали только `ИмяПроекта/src/...`) → 0 объектов | Layout A + B + nested `…/src/…`, `resolveProjectName`, `toWorkspacePath` |
| EDT 2026.2 API | После LoadCfg: `NoSuchMethodError` на `setActualConfigDumpInfo(Path)` | `loadActualConfigDumpInfo(Path)` |
| Залипший sync ИБ | При ошибке после `startUpdateProjectFlow` flow без `finish()`/`cancel()` | `try/finally` + `cancel()` |
| «Поместили» только в ИБ | Захват + LoadCfg без UpdateDBCfg и без commit в хранилище | `/UpdateDBCfg` + `/ConfigurationRepositoryCommit` |
| UI freeze | Долгий цикл на UI-thread, диалоги с worker без `syncExec` | `ProgressMonitorDialog` + `subTask` + `syncExec` |
| Нет pull | Нельзя получить версии из хранилища в EDT | Команда «Получить из хранилища» (`UpdateCfg` → `UpdateDBCfg` → штатный EDT sync) |
| Нет журнала операции | Ошибки Designer плохо диагностируются | `OperationLogger` → файл в state location плагина |
| Жёсткий комментарий | Только `PluginEDT: {ветка}` | Шаблон с placeholders в настройках |

---

## Команды UI (меню «Хранилище конфигурации»)

| Команда | Где | Назначение |
|---------|-----|------------|
| **Поместить в хранилище** | Development view, дескриптор ветки git↔ИБ | Push: lock → CompareCfg → export EDT → LoadCfg → UpdateDBCfg → Commit |
| **Получить из хранилища** | Development view, дескриптор ветки git↔ИБ | Pull: UpdateCfg (-revised -force) → UpdateDBCfg → sync ИБ→EDT |
| **Настройки** | Development view / Navigator (проект) | Адрес/логин/пароль хранилища, опции, **шаблон комментария** |

**Не включено** из upstream PR #3 (осознанно): «Получить в задачу…», baseline ветки хранилища, auto-merge storage branch, lock navigation (#4).

---

## Изменения форка (полный список)

### 1. Поддержка single-project git (`src/...`) и nested `…/src/…`

**Зачем:** layout KICB/EDT — `.git` внутри проекта; также `cf/.../src/...`, `cfe/.../src/...`.

**Что:** в `ExportHandler` — layout A/B + поиск сегмента `src`; `toProjectSourcePath` / `toWorkspacePath`.

### 2. EDT 2026.2: `loadActualConfigDumpInfo`

**Зачем:** иначе падение после успешного LoadCfg.

**Что:** в `Designer.loadConfigurationFromXml` — прямой `loadActualConfigDumpInfo(Path)` + `try/finally` + `cancel()`.

### 3. UpdateDBCfg + commit в хранилище

**Что:** `Designer.updateDatabaseConfiguration`, `Designer.storeObjects` (не PR-`commitObjects`).

### 4. Прогресс и UI

**Что:** `ProgressMonitorDialog` на push; `OperationLogDialog` на pull; `subTask` / `syncExec`.

### 5. Получить из хранилища (port PR #3)

**Что:** `ImportHandler` (thin) + `StoragePullService` + `Designer.updateConfigurationFromRepository` / `retrieveConfigurationChangesFromInfobase`.

### 6. Журнал операций

**Что:** `OperationLogger` / `OperationLogDialog`. Лог:  
`.metadata/.plugins/dev.zigr.dt.team.ui.storage/operations/storage-operation-*.log`

### 7. Шаблон комментария помещения

Default: `EDT: {branch}, project {project}, changed files {changedFiles}`  
Placeholders: `{branch}`, `{storageBranch}`, `{project}`, `{changedFiles}`, `{fileCount}`, `{files}`, `{infobase}`.

---

## Ветка

- Патчи UX/UpdateDBCfg: **`fix/storage-feedback`** (смержено в master)
- Port PR #3: **`port/pr3-pull-logging`**

---

## Установка

Готовая сборка форка (p2 archive):
**https://github.com/SergeiPereskokov/PluginEDT/releases/download/v0.4.0-fork.2/build.zip**  
(если Release ещё нет — локально `out/build-fork-v0.4.0-fork.2.zip` / `out/build.zip`)

Tag: `v0.4.0-fork.2`.

### Через «Справка → Установить новое ПО»

1. Скачать `build.zip`.
2. EDT → **Справка → Установить новое ПО…**.
3. **Add…** → **Archive…** → `build.zip` → Next.
4. Feature **Configuration repository** → Finish → перезапуск (при странном sync — `-clean`).

Upstream без патчей: https://github.com/ZigRinat85/PluginEDT/releases/download/v0.4.0/build.zip  
Статья upstream: https://infostart.ru/1c/articles/2442956/

---

## Smoke (EDT 2026.2.0.289)

1. Bundle Active, меню: Поместить / Получить / Настройки (без task/baseline).
2. Настройки: шаблон комментария save/reopen.
3. Поместить: progress, UpdateDBCfg, комментарий по шаблону, лог-файл.
4. Получить: изменения из хранилища в EDT; при `NO_CHANGES` + ожидаемые объекты — ошибка + лог.
5. Single-project `src/...` и nested `cf/…/src/…` (если есть).
6. Sticky sync: нет вечной блокировки ИБ после ошибки mid-flow.

---

## Open source / лицензия

Репозиторий **публичный**. Fork [ZigRinat85/PluginEDT](https://github.com/ZigRinat85/PluginEDT); условия — как у upstream.

---

## Исходники в git

В репозитории — исходники плагина. Сборки `out/`, `pr3.diff`, stubs в `.p2` в git не входят.
