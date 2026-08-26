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
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.xtext.naming.QualifiedName;

import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettings;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAccessType;
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
	private ServiceSupplier<IInfobaseSynchronizationStateManager> infobaseSynchronizationStateManagerSupplier = 
			ServiceAccess.supplier(IInfobaseSynchronizationStateManager.class, StorageUiPlugin.getDefault());	
	
	private IGitBranchIssueDescriptor issueDescriptor;
	private IProject project;
	private Path rootDirectory;
	private Version version;
	private ComponentExecutorInfo<ILaunchableRuntimeComponent, IDesignerSessionThickClientLauncher> thickClient;
	private String extensionName;
	
	public Designer(IGitBranchIssueDescriptor issueDescriptor, String projectName, Path rootDirectory) throws CoreException, IOException, InterruptedException, RuntimeExecutionException {
		this.issueDescriptor = issueDescriptor;
		this.project = getV8ProjectManager().getProject(projectName).getProject();
		this.rootDirectory = rootDirectory;
		
		InfobaseReference infobase = issueDescriptor.getInfobase();
		IResolvableRuntimeInstallation actualInstallation = getResolvableRuntimeInstallationManager().resolveByProjectAndInfobase(
				RuntimeInstallations.ENTERPRISE_PLATFORM, project, infobase, InfobaseAccessType.UPDATE);
		RuntimeInstallation installation = actualInstallation.resolve(List.of(IRuntimeComponentTypes.THICK_CLIENT), infobase.getAppArch());
		version = installation.getVersion();
		thickClient = getRuntimeComponentManager().resolveExecutor(
				ILaunchableRuntimeComponent.class, IDesignerSessionThickClientLauncher.class, installation, IRuntimeComponentTypes.THICK_CLIENT);
		
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
	
	private IInfobaseSynchronizationStateManager getInfobaseSynchronizationStateManager() {
		return infobaseSynchronizationStateManagerSupplier.get();
	}
	
	public void dispose() {
		infobaseAccessManagerSupplier.close();
		runtimeComponentManagerSupplier.close();
		v8ProjectManagerSupplier.close();
		resolvableRuntimeInstallationManagerSupplier.close();
		infobaseSynchronizationStateManagerSupplier.close();
	}
	
	public Version getVersion() {
		return version;
	}
	
	public void closeDesignerSession() throws RuntimeExecutionException {
		closeDesignerSession(null);
	}

	public void closeDesignerSession(IProgressMonitor monitor) throws RuntimeExecutionException {
		subTask(monitor, "Закрытие сессии конфигуратора…");
		thickClient.getExecutor().closeDesignerSession(thickClient.getComponent(), issueDescriptor.getInfobase(), null);
	}

	private RuntimeExecutionCommandBuilder getCommandBuilder(Path log) throws CoreException {
		InfobaseReference infobase = issueDescriptor.getInfobase();
		IInfobaseAccessSettings settings = getInfobaseAccessManager().resolveSettings(infobase);
		File launchFile = thickClient.getComponent().getFile();
		
		RuntimeExecutionCommandBuilder result = new RuntimeExecutionCommandBuilder(launchFile, RuntimeExecutionCommandBuilder.ThickClientMode.DESIGNER)
				.forInfobase(infobase, false).userName(settings.userName()).userPassword(settings.password())
				.disableStartupDialogs().interfaceLanguage("ru").logTo(log.toFile(), true);
		
		return result;
	}

	public void loadConfigurationFromXml(Path sourceFolder, Path fileList)
			throws CoreException, IOException, InterruptedException, RuntimeExecutionException {
		loadConfigurationFromXml(sourceFolder, fileList, null);
	}

	public void loadConfigurationFromXml(Path sourceFolder, Path fileList, IProgressMonitor monitor)
			throws CoreException, IOException, InterruptedException, RuntimeExecutionException {
		Path log = rootDirectory.resolve("loadCfgOut.txt");
		
		subTask(monitor, "Загрузка XML в информационную базу…");
		RuntimeExecutionCommandBuilder command = getCommandBuilder(log)
			.importXmlToInfobase(sourceFolder).fileList(fileList).updateConfigDumpInfo();
		
		if (!extensionName.isEmpty()) {
			command.forExtension(extensionName);
		}
		
		Process process = command.start();
		int returnCode = process.waitFor();
		if (returnCode == 0) {
			// актуализация ConfigDumpInfo.xml в ветке хранилища
			// EDT 2026.2+: setActualConfigDumpInfo(Path) удалён → loadActualConfigDumpInfo(Path)
			subTask(monitor, "Актуализация ConfigDumpInfo…");
			StorageUiPlugin.logInfo("Актуализация ConfigDumpInfo…");
			IUpdateProjectFlow updateProjectFlow = getInfobaseSynchronizationStateManager().startUpdateProjectFlow(
					getV8ProjectManager().getProject(project).getDtProject(), issueDescriptor.getInfobase());
			boolean finished = false;
			try {
				updateProjectFlow.loadActualConfigDumpInfo(sourceFolder); // каталог с ConfigDumpInfo.xml
				// updateProjectFlow.setActualGenerationId(retrieveGenerationId()); для нас необязательно
				updateProjectFlow.finish();
				finished = true;
			} finally {
				// без finish() sync flow залипает; при ошибке — best-effort cancel
				if (!finished) {
					try {
						updateProjectFlow.cancel();
					} catch (Exception ignore) {
						// sticky sync: best-effort cancel
					}
				}
			}
		}
		else {
			IStatus status = StorageUiPlugin.createErrorStatus(Files.readString(log));
			throw new CoreException(status);
		}
	}
	
	public void lockObjects(Map<QualifiedName, Boolean> lockObjects) throws IOException, CoreException, InterruptedException {
		lockObjects(lockObjects, null);
	}

	public void lockObjects(Map<QualifiedName, Boolean> lockObjects, IProgressMonitor monitor)
			throws IOException, CoreException, InterruptedException {
		subTask(monitor, MessageFormat.format("Захват объектов в хранилище… ({0})", lockObjects.size()));
		Path lockObjectsList = rootDirectory.resolve("lockObjectsList.xml");
		writeObjectsListXml(lockObjects, lockObjectsList);
		
		Path log = rootDirectory.resolve("lockObjectsOut.txt");
		RuntimeExecutionCommandBuilder command = getCommandBuilder(log);
		Settings storageSettings = new Settings(project.getName());
		String additionalStartupParameters = buildRepositoryAuthParams(storageSettings)
		+ " /ConfigurationRepositoryLock -Objects " + lockObjectsList.toString()
		+ "{0}";
		
		if (!extensionName.isEmpty()) {
			command.additionalParameters(MessageFormat.format(additionalStartupParameters, " -Extension " + extensionName));
		}
		else {
			command.additionalParameters(MessageFormat.format(additionalStartupParameters, ""));
		}
		
		Process process = command.start();
		int returnCode = process.waitFor();
		if (returnCode != 0) {
			if (!extensionName.isEmpty()) { // имя расширения могло быть переименовано в EDT
				subTask(monitor, "Поиск расширения в ИБ…");
				StorageUiPlugin.logInfo("Поиск расширения в ИБ…");
				Path logListExtNames = rootDirectory.resolve("listExtNamesOut.txt");
				command = getCommandBuilder(logListExtNames);
				command.listConfigurationExtensions();
				process = command.start();
				returnCode = process.waitFor();
				if (returnCode != 0) {
					IStatus status = StorageUiPlugin.createErrorStatus(Files.readString(logListExtNames));
					throw new CoreException(status);
				}
				else {
					try (BufferedReader reader = new BufferedReader(new FileReader(logListExtNames.toString(),StandardCharsets.UTF_8))) {
						String line;
						boolean extensionIsFound = false;
						while ((line = reader.readLine()) != null) {
							subTask(monitor, MessageFormat.format("Повторный захват: {0}", line));
							StorageUiPlugin.logInfo(MessageFormat.format("Повторный захват: {0}", line));
							Path logExtensionLockObjects = rootDirectory.resolve("extensionObjectsOut.txt");
							command = getCommandBuilder(logExtensionLockObjects);
							command.additionalParameters(MessageFormat.format(additionalStartupParameters, " -Extension " + line));
							process = command.start();
							returnCode = process.waitFor();
							if (returnCode == 0) {
								extensionName = line;
								extensionIsFound = true;
								break;
							}
						}
						if (!extensionIsFound) {
							IStatus status = StorageUiPlugin.createErrorStatus("В ИБ не обнаружено расширение, подключенное к хранилищу "
										+ storageSettings.getAddress());
							throw new CoreException(status);
						}
					} catch (IOException e) {
						throw e;
					}
				}
			} 
			else {
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
		subTask(monitor, "Обновление конфигурации БД (UpdateDBCfg)…");
		StorageUiPlugin.logInfo("Обновление конфигурации БД (UpdateDBCfg)…");
		Path log = rootDirectory.resolve("updateDbCfgOut.txt");
		RuntimeExecutionCommandBuilder command = getCommandBuilder(log).updateDatabaseConfiguration();
		if (!extensionName.isEmpty()) {
			command.forExtension(extensionName);
		}
		Process process = command.start();
		int returnCode = process.waitFor();
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
		subTask(monitor, MessageFormat.format("Помещение объектов в хранилище… ({0})", objects.size()));
		StorageUiPlugin.logInfo(MessageFormat.format(
				"Помещение объектов в хранилище… ({0}), comment={1}", objects.size(), comment));
		Path storeObjectsList = rootDirectory.resolve("storeObjectsList.xml");
		writeObjectsListXml(objects, storeObjectsList);

		String safeComment = sanitizeRepositoryComment(comment);
		Path log = rootDirectory.resolve("storeObjectsOut.txt");
		RuntimeExecutionCommandBuilder command = getCommandBuilder(log);
		Settings storageSettings = new Settings(project.getName());
		String additionalStartupParameters = buildRepositoryAuthParams(storageSettings)
				+ " /ConfigurationRepositoryCommit -Objects " + storeObjectsList.toString()
				+ " -comment \"" + safeComment + "\""
				+ "{0}";

		if (!extensionName.isEmpty()) {
			command.additionalParameters(MessageFormat.format(additionalStartupParameters, " -Extension " + extensionName));
		} else {
			command.additionalParameters(MessageFormat.format(additionalStartupParameters, ""));
		}

		Process process = command.start();
		int returnCode = process.waitFor();
		if (returnCode != 0) {
			IStatus status = StorageUiPlugin.createErrorStatus(Files.readString(log));
			throw new CoreException(status);
		}
	}

	private static String buildRepositoryAuthParams(Settings storageSettings) {
		return "/ConfigurationRepositoryF " + storageSettings.getAddress()
				+ " /ConfigurationRepositoryN " + storageSettings.getUser()
				+ (storageSettings.getPassword().isEmpty() ? "" : " /ConfigurationRepositoryP " + storageSettings.getPassword());
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
		return isConfigurationSame(null);
	}

	public boolean isConfigurationSame(IProgressMonitor monitor) throws CoreException, IOException, InterruptedException {
		subTask(monitor, "Сравнение конфигурации с конфигурацией БД…");
		Path log = rootDirectory.resolve("compareCfgOut.txt");
		Path reportFile = rootDirectory.resolve("compareCfgReport.txt");
		
		RuntimeExecutionCommandBuilder command = getCommandBuilder(log);
		
		String additionalStartupParameters = "/CompareCfg "
			+ "-FirstConfigurationType {0} -SecondConfigurationType {1} "
			+ "-IncludeChangedObjects -IncludeDeletedObjects -IncludeAddedObjects "
			+ "-ReportType Brief -ReportFormat txt "
			+ "-ReportFile " + reportFile.toString();
		
		if (!extensionName.isEmpty()) {
			additionalStartupParameters = MessageFormat.format(additionalStartupParameters, 
				"ExtensionConfiguration -FirstName "+extensionName, "ExtensionDBConfiguration -SecondName "+extensionName);
		}
		else {
			additionalStartupParameters = MessageFormat.format(additionalStartupParameters, "MainConfiguration", "DBConfiguration");
		}
		
		command.additionalParameters(additionalStartupParameters);
		
		Process process = command.start();
		int returnCode = process.waitFor();
		if (returnCode != 0) {
			IStatus status = StorageUiPlugin.createErrorStatus(Files.readString(log));
			throw new CoreException(status);
		}
		
		long lineCount = 0;
		try (BufferedReader reader = new BufferedReader(new FileReader(reportFile.toString(),StandardCharsets.UTF_16))) { // тут UTF_16 почему-то
			lineCount = reader.lines().count();
		} catch (IOException e) {
			throw e;
		}
		
		if (lineCount == 6) { // нет изменений
			return true;
		} else {
			subTask(monitor, "Обнаружены отличия конфигурации от БД");
			StorageUiPlugin.logInfo("Обнаружены отличия конфигурации от БД");
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

	private static void subTask(IProgressMonitor monitor, String message) {
		if (monitor != null) {
			monitor.subTask(message);
		}
	}

}
