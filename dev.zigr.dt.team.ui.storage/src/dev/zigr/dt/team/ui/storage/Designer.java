package dev.zigr.dt.team.ui.storage;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.naming.QualifiedName;

import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettings;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAccessType;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseChangesResolver;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseConfigurationChange;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateConflictResolver;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateConflictResolver.IConflictResolveAssist;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseChangesResolutionResult;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseConflictResolution;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseConflictResolutionResult;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseSyncResolution;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseSynchronizationException;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.ObjectChange;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.ObjectChangeType;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationFlow;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationStateManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IUpdateProjectFlow;
import com._1c.g5.v8.dt.platform.services.core.runtimes.RuntimeInstallations;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallation;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ComponentExecutorInfo;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IDesignerSessionThickClientLauncher;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ILaunchableRuntimeComponent;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentTypes;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionException;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.impl.RuntimeExecutionCommandBuilder;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.v8.dt.team.git.infobases.IGitBranchIssueDescriptor;
import com._1c.g5.wiring.ServiceAccess;
import com._1c.g5.wiring.ServiceSupplier;

public class Designer {

	private ServiceSupplier<IInfobaseAccessManager> infobaseAccessManagerSupplier =
			ServiceAccess.supplier(IInfobaseAccessManager.class, StorageUiPlugin.getDefault());
	private ServiceSupplier<IRuntimeComponentManager> runtimeComponentManagerSupplier =
			ServiceAccess.supplier(IRuntimeComponentManager.class, StorageUiPlugin.getDefault());
	private ServiceSupplier<IV8ProjectManager> v8ProjectManagerSupplier =
			ServiceAccess.supplier(IV8ProjectManager.class, StorageUiPlugin.getDefault());
	private ServiceSupplier<IResolvableRuntimeInstallationManager> resolvableRuntimeInstallationManagerSupplier =
			ServiceAccess.supplier(IResolvableRuntimeInstallationManager.class, StorageUiPlugin.getDefault());
	private ServiceSupplier<IInfobaseSynchronizationManager> infobaseSynchronizationManagerSupplier =
			ServiceAccess.supplier(IInfobaseSynchronizationManager.class, StorageUiPlugin.getDefault());
	private ServiceSupplier<IInfobaseSynchronizationStateManager> infobaseSynchronizationStateManagerSupplier =
			ServiceAccess.supplier(IInfobaseSynchronizationStateManager.class, StorageUiPlugin.getDefault());

	private InfobaseReference infobase;
	private IProject project;
	private Path rootDirectory;
	private Version version;
	private ComponentExecutorInfo<ILaunchableRuntimeComponent, IDesignerSessionThickClientLauncher> thickClient;
	private String extensionName;

	public Designer(IGitBranchIssueDescriptor issueDescriptor, String projectName, Path rootDirectory)
			throws CoreException, IOException, InterruptedException, RuntimeExecutionException {
		this(issueDescriptor.getInfobase(), projectName, rootDirectory);
	}

	public Designer(InfobaseReference infobase, String projectName, Path rootDirectory)
			throws CoreException, IOException, InterruptedException, RuntimeExecutionException {
		this.infobase = infobase;
		this.project = getV8ProjectManager().getProject(projectName).getProject();
		this.rootDirectory = rootDirectory;

		IResolvableRuntimeInstallation actualInstallation = getResolvableRuntimeInstallationManager()
				.resolveByProjectAndInfobase(RuntimeInstallations.ENTERPRISE_PLATFORM, project, infobase,
						InfobaseAccessType.UPDATE);
		RuntimeInstallation installation = actualInstallation.resolve(List.of(IRuntimeComponentTypes.THICK_CLIENT),
				infobase.getAppArch());
		version = installation.getVersion();
		thickClient = getRuntimeComponentManager().resolveExecutor(ILaunchableRuntimeComponent.class,
				IDesignerSessionThickClientLauncher.class, installation, IRuntimeComponentTypes.THICK_CLIENT);

		extensionName = getExtensionName();
	}

	private IInfobaseAccessManager getInfobaseAccessManager() {
		return infobaseAccessManagerSupplier.get();
	}

	private IRuntimeComponentManager getRuntimeComponentManager() {
		return runtimeComponentManagerSupplier.get();
	}

	private IV8ProjectManager getV8ProjectManager() {
		return v8ProjectManagerSupplier.get();
	}

	private IResolvableRuntimeInstallationManager getResolvableRuntimeInstallationManager() {
		return resolvableRuntimeInstallationManagerSupplier.get();
	}

	private IInfobaseSynchronizationManager getInfobaseSynchronizationManager() {
		return infobaseSynchronizationManagerSupplier.get();
	}

	private IInfobaseSynchronizationStateManager getInfobaseSynchronizationStateManager() {
		return infobaseSynchronizationStateManagerSupplier.get();
	}

	public void dispose() {
		infobaseAccessManagerSupplier.close();
		runtimeComponentManagerSupplier.close();
		v8ProjectManagerSupplier.close();
		resolvableRuntimeInstallationManagerSupplier.close();
		infobaseSynchronizationManagerSupplier.close();
		infobaseSynchronizationStateManagerSupplier.close();
	}

	public Version getVersion() {
		return version;
	}

	public IProject getProject() {
		return project;
	}

	public String getStorageTargetDescription() {
		if (extensionName == null || extensionName.isEmpty()) {
			return "основная конфигурация";
		}
		return "расширение " + extensionName;
	}

	public String getResolvedExtensionName() {
		return extensionName;
	}

	public void closeDesignerSession() throws RuntimeExecutionException {
		closeDesignerSession(null);
	}

	public void closeDesignerSession(IProgressMonitor monitor) throws RuntimeExecutionException {
		subTask(monitor, "Закрытие сессии конфигуратора…");
		thickClient.getExecutor().closeDesignerSession(thickClient.getComponent(), infobase, null);
	}

	private RuntimeExecutionCommandBuilder getCommandBuilder(Path log) throws CoreException {
		IInfobaseAccessSettings settings = getInfobaseAccessManager().resolveSettings(infobase);
		File launchFile = thickClient.getComponent().getFile();

		return new RuntimeExecutionCommandBuilder(launchFile, RuntimeExecutionCommandBuilder.ThickClientMode.DESIGNER)
				.forInfobase(infobase, false).userName(settings.userName()).userPassword(settings.password())
				.disableStartupDialogs().interfaceLanguage("ru").logTo(log.toFile(), true);
	}

	public void loadConfigurationFromXml(Path sourceFolder, Path fileList)
			throws CoreException, IOException, InterruptedException, RuntimeExecutionException {
		loadConfigurationFromXml(sourceFolder, fileList, null, null);
	}

	public void loadConfigurationFromXml(Path sourceFolder, Path fileList, IProgressMonitor monitor)
			throws CoreException, IOException, InterruptedException, RuntimeExecutionException {
		loadConfigurationFromXml(sourceFolder, fileList, monitor, null);
	}

	public void loadConfigurationFromXml(Path sourceFolder, Path fileList, IProgressMonitor monitor,
			OperationLogger logger) throws CoreException, IOException, InterruptedException, RuntimeExecutionException {
		Path log = rootDirectory.resolve("loadCfgOut.txt");

		subTask(monitor, "Загрузка XML в информационную базу…");
		RuntimeExecutionCommandBuilder command = getCommandBuilder(log).importXmlToInfobase(sourceFolder)
				.fileList(fileList).updateConfigDumpInfo();

		if (!extensionName.isEmpty()) {
			command.forExtension(extensionName);
		}

		int returnCode = runCommand("Загрузка XML в конфигурацию", command, log, logger);
		if (returnCode == 0) {
			// актуализация ConfigDumpInfo.xml в ветке хранилища
			// EDT 2026.2+: setActualConfigDumpInfo(Path) удалён → loadActualConfigDumpInfo(Path)
			subTask(monitor, "Актуализация ConfigDumpInfo…");
			StorageUiPlugin.logInfo("Актуализация ConfigDumpInfo…");
			if (logger != null) {
				logger.detail("Актуализация ConfigDumpInfo…");
			}
			IUpdateProjectFlow updateProjectFlow = getInfobaseSynchronizationStateManager().startUpdateProjectFlow(
					getV8ProjectManager().getProject(project).getDtProject(), infobase);
			boolean finished = false;
			try {
				updateProjectFlow.loadActualConfigDumpInfo(sourceFolder); // каталог с ConfigDumpInfo.xml
				updateProjectFlow.finish();
				finished = true;
			} finally {
				if (!finished) {
					try {
						updateProjectFlow.cancel();
					} catch (Exception ignore) {
						// sticky sync: best-effort cancel
					}
				}
			}
		} else {
			IStatus status = StorageUiPlugin.createErrorStatus(Files.readString(log));
			throw new CoreException(status);
		}
	}

	public void lockObjects(Map<QualifiedName, Boolean> lockObjects) throws IOException, CoreException, InterruptedException {
		lockObjects(lockObjects, null, null);
	}

	public void lockObjects(Map<QualifiedName, Boolean> lockObjects, IProgressMonitor monitor)
			throws IOException, CoreException, InterruptedException {
		lockObjects(lockObjects, monitor, null);
	}

	public void lockObjects(Map<QualifiedName, Boolean> lockObjects, IProgressMonitor monitor, OperationLogger logger)
			throws IOException, CoreException, InterruptedException {
		subTask(monitor, MessageFormat.format("Захват объектов в хранилище… ({0})", lockObjects.size()));
		Path lockObjectsList = rootDirectory.resolve("lockObjectsList.xml");
		writeObjectsListXml(lockObjects, lockObjectsList);

		Path log = rootDirectory.resolve("lockObjectsOut.txt");
		RuntimeExecutionCommandBuilder command = getCommandBuilder(log);
		Settings storageSettings = new Settings(project.getName());
		String additionalStartupParameters = getRepositoryConnectionParameters()
				+ " /ConfigurationRepositoryLock -Objects " + quoteParameter(lockObjectsList.toString()) + "{0}";

		String lockParameters = MessageFormat.format(additionalStartupParameters,
				extensionName.isEmpty() ? "" : " -Extension " + quoteParameter(extensionName));
		command.additionalParameters(lockParameters);
		logCommandContext(logger, "Захват объектов в хранилище", lockParameters);

		int returnCode = runCommand("Захват объектов в хранилище", command, log, logger);
		if (returnCode != 0) {
			if (!extensionName.isEmpty()) { // имя расширения могло быть переименовано в EDT
				subTask(monitor, "Поиск расширения в ИБ…");
				StorageUiPlugin.logInfo("Поиск расширения в ИБ…");
				Path logListExtNames = rootDirectory.resolve("listExtNamesOut.txt");
				command = getCommandBuilder(logListExtNames);
				command.listConfigurationExtensions();
				logCommandContext(logger, "Получение списка расширений ИБ", "ListConfigurationExtensions");
				returnCode = runCommand("Получение списка расширений ИБ", command, logListExtNames, logger);
				if (returnCode != 0) {
					IStatus status = StorageUiPlugin.createErrorStatus(Files.readString(logListExtNames));
					throw new CoreException(status);
				} else {
					try (BufferedReader reader = new BufferedReader(
							new FileReader(logListExtNames.toString(), StandardCharsets.UTF_8))) {
						String line;
						boolean extensionIsFound = false;
						while ((line = reader.readLine()) != null) {
							subTask(monitor, MessageFormat.format("Повторный захват: {0}", line));
							StorageUiPlugin.logInfo(MessageFormat.format("Повторный захват: {0}", line));
							Path logExtensionLockObjects = rootDirectory.resolve("extensionObjectsOut.txt");
							command = getCommandBuilder(logExtensionLockObjects);
							String extensionLockParameters = MessageFormat.format(additionalStartupParameters,
									" -Extension " + quoteParameter(line));
							command.additionalParameters(extensionLockParameters);
							logCommandContext(logger, "Захват объектов в расширении " + line, extensionLockParameters);
							returnCode = runCommand("Захват объектов в расширении " + line, command,
									logExtensionLockObjects, logger);
							if (returnCode == 0) {
								extensionName = line;
								extensionIsFound = true;
								break;
							}
						}
						if (!extensionIsFound) {
							IStatus status = StorageUiPlugin.createErrorStatus(
									"В ИБ не обнаружено расширение, подключенное к хранилищу "
											+ storageSettings.getAddress());
							throw new CoreException(status);
						}
					} catch (IOException e) {
						throw e;
					}
				}
			} else {
				IStatus status = StorageUiPlugin.createErrorStatus(Files.readString(log));
				throw new CoreException(status);
			}
		}
	}

	/**
	 * Применить Основную конфигурацию в Конфигурацию БД (/UpdateDBCfg).
	 * После LoadCfg Main≠DB; для помещения в хранилище по схеме статьи — сначала UpdateDBCfg.
	 */
	public void updateDatabaseConfiguration(IProgressMonitor monitor)
			throws CoreException, IOException, InterruptedException {
		updateDatabaseConfiguration(monitor, null);
	}

	public void updateDatabaseConfiguration(OperationLogger logger)
			throws CoreException, IOException, InterruptedException {
		updateDatabaseConfiguration(null, logger);
	}

	public void updateDatabaseConfiguration(IProgressMonitor monitor, OperationLogger logger)
			throws CoreException, IOException, InterruptedException {
		subTask(monitor, "Обновление конфигурации БД (UpdateDBCfg)…");
		StorageUiPlugin.logInfo("Обновление конфигурации БД (UpdateDBCfg)…");
		Path log = rootDirectory.resolve("updateDbCfgOut.txt");
		RuntimeExecutionCommandBuilder command = getCommandBuilder(log).updateDatabaseConfiguration();
		if (!extensionName.isEmpty()) {
			command.forExtension(extensionName);
		}
		String params = "/UpdateDBCfg" + (extensionName.isEmpty() ? "" : " -Extension " + quoteParameter(extensionName));
		logCommandContext(logger, "Обновление конфигурации базы данных", params);
		int returnCode = runCommand("Обновление конфигурации базы данных", command, log, logger);
		if (returnCode != 0) {
			IStatus status = StorageUiPlugin.createErrorStatus(Files.readString(log));
			throw new CoreException(status);
		}
	}

	/**
	 * Поместить захваченные объекты в хранилище конфигурации (/ConfigurationRepositoryCommit).
	 * Без -keepLocked — после успеха захват снимается.
	 */
	public void storeObjects(Map<QualifiedName, Boolean> objects, String comment, IProgressMonitor monitor)
			throws IOException, CoreException, InterruptedException {
		storeObjects(objects, comment, monitor, null);
	}

	public void storeObjects(Map<QualifiedName, Boolean> objects, String comment, IProgressMonitor monitor,
			OperationLogger logger) throws IOException, CoreException, InterruptedException {
		subTask(monitor, MessageFormat.format("Помещение объектов в хранилище… ({0})", objects.size()));
		StorageUiPlugin.logInfo(MessageFormat.format("Помещение объектов в хранилище… ({0}), comment={1}",
				objects.size(), comment));
		Path storeObjectsList = rootDirectory.resolve("storeObjectsList.xml");
		writeObjectsListXml(objects, storeObjectsList);

		String safeComment = sanitizeRepositoryComment(comment);
		Path log = rootDirectory.resolve("storeObjectsOut.txt");
		RuntimeExecutionCommandBuilder command = getCommandBuilder(log);
		String additionalStartupParameters = getRepositoryConnectionParameters()
				+ " /ConfigurationRepositoryCommit -Objects " + quoteParameter(storeObjectsList.toString())
				+ " -comment " + quoteParameter(safeComment) + "{0}";

		String commitParameters = MessageFormat.format(additionalStartupParameters,
				extensionName.isEmpty() ? "" : " -Extension " + quoteParameter(extensionName));
		command.additionalParameters(commitParameters);
		logCommandContext(logger, "Помещение изменений в хранилище", commitParameters);

		int returnCode = runCommand("Помещение изменений в хранилище", command, log, logger);
		if (returnCode != 0) {
			IStatus status = StorageUiPlugin.createErrorStatus(Files.readString(log));
			throw new CoreException(status);
		}
	}

	public List<String> updateConfigurationFromRepository(OperationLogger logger)
			throws CoreException, IOException, InterruptedException {
		Path log = rootDirectory.resolve("updateCfgOut.txt");
		RuntimeExecutionCommandBuilder command = getCommandBuilder(log);
		String additionalStartupParameters = getRepositoryConnectionParameters()
				+ " /ConfigurationRepositoryUpdateCfg -revised -force";
		if (!extensionName.isEmpty()) {
			additionalStartupParameters = additionalStartupParameters + " -Extension " + quoteParameter(extensionName);
		}
		command.additionalParameters(additionalStartupParameters);
		logCommandContext(logger, "Получение конфигурации из хранилища", additionalStartupParameters);

		int returnCode = runCommand("Получение конфигурации из хранилища", command, log, logger);
		if (returnCode != 0) {
			IStatus status = StorageUiPlugin.createErrorStatus(Files.readString(log));
			throw new CoreException(status);
		}
		List<String> updatedObjects = readUpdatedRepositoryObjects(log);
		if (logger != null) {
			logger.detail("Из хранилища получено объектов: " + updatedObjects.size());
			for (String objectName : updatedObjects) {
				logger.detail("Из хранилища получен объект: " + objectName);
			}
		}
		return updatedObjects;
	}

	public InfobaseChangesResolutionResult retrieveConfigurationChangesFromInfobase(OperationLogger logger,
			IProgressMonitor monitor) throws CoreException {
		IProgressMonitor actualMonitor = monitor != null ? monitor : new NullProgressMonitor();
		if (logger != null) {
			logger.detail("Штатное получение изменений из ИБ в EDT-проект");
			logger.detail("EDT-проект: " + project.getName());
			logger.detail("ИБ: " + infobase.getName());
			logger.detail("Сервис синхронизации EDT: " + getInfobaseSynchronizationManager().getClass().getName());
		}

		InfobaseSyncResolution resolution = getInfobaseSynchronizationManager().retrieveInfobaseChanges(project,
				infobase, new PullChangesResolver(logger), true, actualMonitor);
		logInfobaseSyncResolution(resolution, logger);
		IStatus asyncConflictStatus = waitForConflictResolution(resolution, logger);
		ensureInfobaseSyncResolved(resolution, asyncConflictStatus, logger);
		try {
			project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
		} catch (CoreException e) {
			throw e;
		}
		if (logger != null) {
			logger.detail("EDT-проект обновлен из ИБ штатным механизмом");
		}
		return resolution.getInfobaseChangesResolutionResult();
	}

	private IStatus waitForConflictResolution(InfobaseSyncResolution resolution, OperationLogger logger)
			throws CoreException {
		if (resolution == null || resolution.getConflictResolution() == null) {
			return null;
		}
		CompletableFuture<IStatus> conflictResolution = resolution.getConflictResolution();
		try {
			IStatus status = conflictResolution.get();
			logStatus("Результат асинхронного разрешения конфликтов EDT", status, logger);
			if (status != null && status.matches(IStatus.ERROR | IStatus.CANCEL)) {
				throw new CoreException(status);
			}
			return status;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new CoreException(StorageUiPlugin.createErrorStatus(
					"Получение изменений из ИБ в EDT прервано во время ожидания разрешения конфликтов", e));
		} catch (ExecutionException e) {
			throw new CoreException(StorageUiPlugin.createErrorStatus(
					"Ошибка при асинхронном разрешении конфликтов EDT", e.getCause() != null ? e.getCause() : e));
		}
	}

	private void ensureInfobaseSyncResolved(InfobaseSyncResolution resolution, IStatus asyncConflictStatus,
			OperationLogger logger) throws CoreException {
		if (resolution == null) {
			throw new CoreException(
					StorageUiPlugin.createErrorStatus("EDT не вернула результат получения изменений из ИБ"));
		}
		InfobaseChangesResolutionResult result = resolution.getInfobaseChangesResolutionResult();
		if (resolution.isFinished() && (result == InfobaseChangesResolutionResult.NO_CHANGES
				|| result == InfobaseChangesResolutionResult.CHANGES_RESOLVED)) {
			return;
		}
		if (resolution.isFinished() && result == InfobaseChangesResolutionResult.CHANGES_NOT_RESOLVED
				&& asyncConflictStatus != null && !asyncConflictStatus.matches(IStatus.ERROR | IStatus.CANCEL)) {
			if (logger != null) {
				logger.detail(
						"EDT вернула CHANGES_NOT_RESOLVED, но отложенное разрешение завершилось успешно; считаем импорт выполненным");
			}
			return;
		}
		throw new CoreException(StorageUiPlugin.createErrorStatus(
				"EDT не смогла применить изменения из ИБ. result=" + result + ", finished=" + resolution.isFinished()));
	}

	private void logInfobaseSyncResolution(InfobaseSyncResolution resolution, OperationLogger logger) {
		if (logger == null) {
			return;
		}
		if (resolution == null) {
			logger.detail("Результат штатного получения из ИБ: null");
			return;
		}
		logger.detail("Результат штатного получения из ИБ: result=" + resolution.getInfobaseChangesResolutionResult()
				+ ", finished=" + resolution.isFinished() + ", hasAsyncConflictResolution="
				+ (resolution.getConflictResolution() != null));
	}

	private void logStatus(String title, IStatus status, OperationLogger logger) {
		if (logger == null) {
			return;
		}
		if (status == null) {
			logger.detail(title + ": статус не возвращен");
			return;
		}
		logger.detail(title + ": severity=" + status.getSeverity() + ", message=" + status.getMessage());
		for (IStatus child : status.getChildren()) {
			logger.detail(title + " / child: severity=" + child.getSeverity() + ", message=" + child.getMessage());
		}
	}

	private List<String> readUpdatedRepositoryObjects(Path log) throws IOException {
		List<String> result = new ArrayList<String>();
		String marker = "Объект получен из хранилища:";
		for (String rawLine : Files.readString(log).split("\\R")) {
			String line = rawLine.replace("\uFEFF", "").trim();
			int markerIndex = line.indexOf(marker);
			if (markerIndex >= 0) {
				result.add(line.substring(markerIndex + marker.length()).trim());
			}
		}
		return result;
	}

	private static void writeObjectsListXml(Map<QualifiedName, Boolean> objects, Path target) throws IOException {
		String strTemplate = "<Object fullName = \"{0}\" includeChildObjects = \"{1}\" />";
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(target.toString(), StandardCharsets.UTF_8))) {
			writer.append("<Objects xmlns=\"http://v8.1c.ru/8.3/config/objects\" version=\"1.0\">" + System.lineSeparator());
			for (Map.Entry<QualifiedName, Boolean> entry : objects.entrySet()) {
				QualifiedName key = entry.getKey();
				Boolean val = entry.getValue();
				if ("Configuration".equals(key.toString())) {
					writer.append("<Configuration includeChildObjects = \"false\" />" + System.lineSeparator());
				} else {
					writer.append(MessageFormat.format(strTemplate, key.toString(), val.toString()) + System.lineSeparator());
				}
			}
			writer.append("</Objects>");
		}
	}

	static String sanitizeRepositoryComment(String comment) {
		if (comment == null || comment.isBlank()) {
			return "PluginEDT: place from EDT";
		}
		String result = comment.trim();
		if (result.startsWith("refs/heads/")) {
			result = result.substring("refs/heads/".length());
		} else if (result.startsWith("refs/remotes/")) {
			result = result.substring("refs/remotes/".length());
		}
		// кавычки ломают cmdline -comment "..."
		result = result.replace('"', '\'');
		result = result.replace("\r", " ").replace("\n", " ");
		if (result.length() > 200) {
			result = result.substring(0, 200);
		}
		return result;
	}

	public boolean isConfigurationSame() throws CoreException, IOException, InterruptedException {
		return isConfigurationSame(null, null);
	}

	public boolean isConfigurationSame(IProgressMonitor monitor) throws CoreException, IOException, InterruptedException {
		return isConfigurationSame(monitor, null);
	}

	public boolean isConfigurationSame(IProgressMonitor monitor, OperationLogger logger)
			throws CoreException, IOException, InterruptedException {
		subTask(monitor, "Сравнение конфигурации с конфигурацией БД…");
		Path log = rootDirectory.resolve("compareCfgOut.txt");
		Path reportFile = rootDirectory.resolve("compareCfgReport.txt");

		RuntimeExecutionCommandBuilder command = getCommandBuilder(log);

		String additionalStartupParameters = "/CompareCfg "
				+ "-FirstConfigurationType {0} -SecondConfigurationType {1} "
				+ "-IncludeChangedObjects -IncludeDeletedObjects -IncludeAddedObjects "
				+ "-ReportType Brief -ReportFormat txt " + "-ReportFile " + quoteParameter(reportFile.toString());

		if (!extensionName.isEmpty()) {
			additionalStartupParameters = MessageFormat.format(additionalStartupParameters,
					"ExtensionConfiguration -FirstName " + quoteParameter(extensionName),
					"ExtensionDBConfiguration -SecondName " + quoteParameter(extensionName));
		} else {
			additionalStartupParameters = MessageFormat.format(additionalStartupParameters, "MainConfiguration",
					"DBConfiguration");
		}

		command.additionalParameters(additionalStartupParameters);
		logCommandContext(logger, "Сравнение конфигурации с конфигурацией БД", additionalStartupParameters);

		int returnCode = runCommand("Сравнение конфигурации с конфигурацией БД", command, log, logger);
		if (returnCode != 0) {
			IStatus status = StorageUiPlugin.createErrorStatus(Files.readString(log));
			throw new CoreException(status);
		}

		long lineCount = 0;
		try (BufferedReader reader = new BufferedReader(
				new FileReader(reportFile.toString(), StandardCharsets.UTF_16))) { // тут UTF_16 почему-то
			lineCount = reader.lines().count();
		} catch (IOException e) {
			throw e;
		}

		if (lineCount == 6) { // нет изменений
			if (logger != null) {
				logger.detail("Сравнение: конфигурация и конфигурация БД совпадают");
			}
			return true;
		} else {
			subTask(monitor, "Обнаружены отличия конфигурации от БД");
			StorageUiPlugin.logInfo("Обнаружены отличия конфигурации от БД");
			if (logger != null) {
				logger.detail("Сравнение: найдены отличия, строк в отчете: " + lineCount + ", отчет=" + reportFile);
			}
			return false;
		}
	}

	public String getExtensionName() throws CoreException, IOException, InterruptedException {
		String result = "";
		IV8Project v8Project = getV8ProjectManager().getProject(project);
		if (v8Project instanceof IExtensionProject extensionProject) {
			result = extensionProject.getConfiguration().getName();
		}

		return result;
	}

	public String retrieveGenerationId() throws CoreException, IOException, InterruptedException {
		Path log = rootDirectory.resolve("generationIdOut.txt");

		RuntimeExecutionCommandBuilder command = getCommandBuilder(log).additionalParameters("/GetConfigGenerationID");

		Process process = command.start();
		int returnCode = process.waitFor();
		if (returnCode != 0) {
			IStatus status = StorageUiPlugin.createErrorStatus(Files.readString(log));
			throw new CoreException(status);
		}

		String result = Files.readString(log);
		result = result.replaceAll("\r\n", "");

		return result;
	}

	private String getRepositoryConnectionParameters() {
		Settings storageSettings = new Settings(project.getName());
		return "/ConfigurationRepositoryF " + quoteParameter(storageSettings.getAddress())
				+ " /ConfigurationRepositoryN " + quoteParameter(storageSettings.getUser())
				+ (storageSettings.getPassword().isEmpty() ? ""
						: " /ConfigurationRepositoryP " + quoteParameter(storageSettings.getPassword()));
	}

	private int runCommand(String title, RuntimeExecutionCommandBuilder command, Path log, OperationLogger logger)
			throws IOException, InterruptedException {
		if (logger != null) {
			logger.detail(title + ": запуск пакетной команды");
		}
		Process process = command.start();
		if (logger != null) {
			logger.detail(title + ": процесс запущен, log=" + log);
		}
		int loggedLength = 0;
		while (true) {
			if (logger != null) {
				loggedLength = logNewCommandOutput(title, log, logger, loggedLength);
			}
			if (process.waitFor(500, TimeUnit.MILLISECONDS)) {
				break;
			}
		}
		if (logger != null) {
			logNewCommandOutput(title, log, logger, loggedLength);
		}
		int returnCode = process.exitValue();
		if (logger != null) {
			logger.commandResult(title, log, returnCode, false);
		}
		return returnCode;
	}

	private int logNewCommandOutput(String title, Path log, OperationLogger logger, int loggedLength)
			throws IOException {
		String output;
		try {
			if (!Files.exists(log)) {
				return loggedLength;
			}
			output = Files.readString(log);
		} catch (IOException e) {
			return loggedLength;
		}
		if (output.length() < loggedLength) {
			loggedLength = 0;
		}
		if (output.length() == loggedLength) {
			return loggedLength;
		}
		if (loggedLength == 0) {
			logger.detail(title + " output:");
		}
		String newOutput = output.substring(loggedLength);
		for (String line : newOutput.split("\\R")) {
			if (!line.isEmpty()) {
				logger.detail("  " + line);
			}
		}
		return output.length();
	}

	private void logCommandContext(OperationLogger logger, String title, String additionalParameters) {
		if (logger == null) {
			return;
		}
		logger.detail(title + ": проект=" + project.getName() + ", цель=" + getStorageTargetDescription());
		logger.detail(title + ": исполняемый файл=" + thickClient.getComponent().getFile());
		logger.detail(title + ": ИБ=" + infobase.getName());
		logger.detail(title + ": пакетная команда=DESIGNER " + maskSensitiveParameters(additionalParameters));
	}

	private String maskSensitiveParameters(String parameters) {
		return parameters.replaceAll("(?i)(/ConfigurationRepositoryP\\s+)\"[^\"]*\"", "$1\"******\"");
	}

	private String quoteParameter(String value) {
		return "\"" + value.replace("\"", "'") + "\"";
	}

	private static void subTask(IProgressMonitor monitor, String message) {
		if (monitor != null) {
			monitor.subTask(message);
		}
	}

	private final class PullChangesResolver implements IInfobaseChangesResolver {

		private static final int MAX_LOGGED_OBJECT_CHANGES = 100;

		private final OperationLogger logger;

		private PullChangesResolver(OperationLogger logger) {
			this.logger = logger;
		}

		@Override
		public InfobaseConflictResolution resolveInfobaseChanges(IProject syncProject, InfobaseReference syncInfobase,
				Set<EObject> projectNewObjects, Set<EObject> projectModifiedObjects, Set<String> projectDeletedObjects,
				IInfobaseConfigurationChange infobaseChanges, IInfobaseUpdateConflictResolver conflictResolver,
				IConflictResolveAssist conflictResolveAssist, IInfobaseSynchronizationFlow synchronizationFlow,
				IProgressMonitor monitor) throws InfobaseSynchronizationException {
			logProjectChanges(projectNewObjects, projectModifiedObjects, projectDeletedObjects);
			logInfobaseChanges(infobaseChanges);
			if (infobaseChanges == null || (!infobaseChanges.isFullReloadRequired() && infobaseChanges.isEmpty())) {
				if (logger != null) {
					logger.detail("EDT не обнаружила входящих изменений ИБ");
				}
				return new InfobaseConflictResolution(InfobaseConflictResolutionResult.OVERRIDDEN);
			}
			InfobaseConflictResolution resolution = conflictResolver.resolveConflict(syncProject, syncInfobase,
					projectNewObjects, projectModifiedObjects, projectDeletedObjects, infobaseChanges,
					conflictResolveAssist, synchronizationFlow, monitor);
			if (logger != null) {
				logger.detail("Результат разрешения изменений ИБ: " + describeConflictResolution(resolution));
			}
			return resolution;
		}

		private void logProjectChanges(Set<EObject> projectNewObjects, Set<EObject> projectModifiedObjects,
				Set<String> projectDeletedObjects) {
			if (logger == null) {
				return;
			}
			logger.detail("Локальные изменения EDT перед импортом из ИБ: new=" + size(projectNewObjects)
					+ ", modified=" + size(projectModifiedObjects) + ", deleted=" + size(projectDeletedObjects));
		}

		private void logInfobaseChanges(IInfobaseConfigurationChange infobaseChanges) {
			if (logger == null) {
				return;
			}
			if (infobaseChanges == null) {
				logger.detail("EDT вернула пустое описание изменений ИБ");
				return;
			}
			if (infobaseChanges.isFullReloadRequired()) {
				logger.detail("Входящие изменения ИБ: fullReloadRequired=true, объектный список EDT не предоставляет");
				return;
			}
			Set<ObjectChange> objectChanges = infobaseChanges.getObjectChanges();
			logger.detail("Входящие изменения ИБ: objects=" + size(objectChanges) + ", fullReloadRequired="
					+ infobaseChanges.isFullReloadRequired() + ", new="
					+ countChanges(objectChanges, ObjectChangeType.NEW) + ", modified="
					+ countChanges(objectChanges, ObjectChangeType.MODIFIED) + ", deleted="
					+ countChanges(objectChanges, ObjectChangeType.DELETED));
			if (objectChanges == null) {
				return;
			}
			int logged = 0;
			for (ObjectChange change : objectChanges) {
				if (logged >= MAX_LOGGED_OBJECT_CHANGES) {
					logger.detail(
							"Входящие изменения ИБ: ... еще " + (objectChanges.size() - MAX_LOGGED_OBJECT_CHANGES));
					break;
				}
				logger.detail(
						"Входящее изменение ИБ: " + change.getType() + " " + change.getPlatformQualifiedName());
				logged++;
			}
		}

		private int countChanges(Set<ObjectChange> changes, ObjectChangeType type) {
			if (changes == null) {
				return 0;
			}
			int result = 0;
			for (ObjectChange change : changes) {
				if (change.getType() == type) {
					result++;
				}
			}
			return result;
		}

		private int size(Set<?> values) {
			return values == null ? 0 : values.size();
		}

		private String describeConflictResolution(InfobaseConflictResolution resolution) {
			if (resolution == null) {
				return "null";
			}
			return "result=" + resolution.getResolutionResult() + ", hasAsyncStatus="
					+ (resolution.getConflictResolution() != null);
		}
	}

}
