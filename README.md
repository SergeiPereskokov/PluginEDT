## Ссылка на файл плагина

https://github.com/ZigRinat85/PluginEDT/releases/download/v0.4.0/build.zip

## Статья на ![лого инфостарт](https://infostart.ru/bitrix/templates/sandbox_empty/assets/tpl/abo/img/logo.svg)

https://infostart.ru/1c/articles/2442956/

## Новое в версии
---
Новое в версии 0.4.0:
- Добавлена поддержка EDT 2025.2 (ранние версии EDT не поддерживаются)
- Исправлены мелкие ошибки

---
Новое в версии 0.3.0:
- Добавлена поддержка когда конфигурация и ее расширения в одном
репозитории (ранее только в разных репозиториях).
- Настройки плагина теперь хранятся в разрезе "Проект" (ранее
было "ИБ + Проект")
- Команда вызова формы настроек перемещена в контекстное меню
Проекта на панели "Разработка" (ранее располагалась в контекстном
меню Задачи). Описание панели "Разработка" см. https://its.1c.ru/db/edtdoc#content:10229:hdoc
- Команда вызова формы настроек добавлена в контекстное меню
Проекта на панели "Навигатор"
---
Новое в версии 0.2.0:
- Добавлена поддержка расширений конфигураций
- Исправлена ошибка помещения дополнительных индексов из EDT
в ИБ
- Устранены мелкие недочеты и немного ускорена операция помещения
- Настройки плагина теперь хранятся в разрезе "ИБ + Проект" (ранее
было "ИБ")
- Добавлена новая настройка "Помещать даже если конфигурации
различаются"

## Изменения форка (SergeiPereskokov)

Ветка: `fix/storage-feedback`. База: upstream [ZigRinat85/PluginEDT](https://github.com/ZigRinat85/PluginEDT) v0.4.0.

### Что ломалось в upstream

1. **Single-project git layout** — diff с путями `src/...` (репозиторий внутри проекта EDT) полностью игнорировался: плагин ждал только `ИмяПроекта/src/...`. В итоге «Поместить в хранилище» находило 0 объектов.
2. **EDT 2026.2 API** — после LoadCfg падение `NoSuchMethodError`: `IUpdateProjectFlow.setActualConfigDumpInfo(Path)` удалён, нужен `loadActualConfigDumpInfo(Path)`.
3. **Только загрузка в ИБ** — захват + LoadCfg без UpdateDBCfg и без `/ConfigurationRepositoryCommit`: изменения оставались в основной конфигурации ИБ, в хранилище 1С не попадали.
4. **UI freeze** — длинный цикл на UI-thread без прогресса; диалоги с worker-thread без `syncExec`.

### Что починили

**ExportHandler**
- Layout A (`Project/src/...`) и Layout B (`src/...` + `resolveProjectName` по work tree).
- `toWorkspacePath` для FQN-конвертера EDT.
- `ProgressMonitorDialog` + `subTask` по шагам (diff, захват, CompareCfg, export, LoadCfg, UpdateDBCfg, commit).
- UI-диалоги через `Display.syncExec`.
- После LoadCfg: `updateDatabaseConfiguration` + `storeObjects` с комментарием `PluginEDT: {ветка}`.

**Designer**
- `loadActualConfigDumpInfo` вместо `setActualConfigDumpInfo` (EDT 2026.2+).
- `updateDatabaseConfiguration` (`/UpdateDBCfg`).
- `storeObjects` (`/ConfigurationRepositoryCommit`).
- Перегрузка методов с `IProgressMonitor` + `subTask` / лог.

Сборки stubs/out/class в `.p2` в git не входят — только исходники.

