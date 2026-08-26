package dev.zigr.dt.team.ui.storage;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IHandler;
import org.eclipse.core.commands.IHandlerListener;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.RenameDetector;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.xtext.naming.QualifiedName;

import com._1c.g5.v8.bm.core.BmPlatform;
import com._1c.g5.v8.bm.core.IBmNamespace;
import com._1c.g5.v8.bm.core.IBmPlatformTransaction;
import com._1c.g5.v8.dt.common.FileUtil;
import com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.export.IExportOperation;
import com._1c.g5.v8.dt.export.IExportOperationFactory;
import com._1c.g5.v8.dt.export.IExportStrategy;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionException;
import com._1c.g5.v8.dt.team.git.infobases.IGitBranchIssueDescriptor;
import com.google.inject.Inject;

public class ExportHandler implements IHandler {

	@Inject
	private IQualifiedNameFilePathConverter qualifiedNameFilePathConverter;
	@Inject
	private IBmModelManager modelManager;
	@Inject
	private IExportOperationFactory exportOperationFactory;
	
	private Shell shell;
	private IGitBranchIssueDescriptor issueDescriptor;
	private Settings storageSettings;

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		shell = HandlerUtil.getActiveShell(event);
		
		MessageBox dialog = new MessageBox(shell, SWT.ICON_QUESTION | SWT.YES | SWT.NO);
		dialog.setText("Поместить в хранилище");
		dialog.setMessage("Уверены?");
		if (dialog.open() == SWT.NO) {
			return null;
		}
		
		IStructuredSelection selection = HandlerUtil.getCurrentStructuredSelection(event);
		Object firstElement = selection.getFirstElement();
		issueDescriptor = (IGitBranchIssueDescriptor) Adapters.adapt(firstElement, IGitBranchIssueDescriptor.class);
		
		AtomicBoolean result = new AtomicBoolean(true);
		AtomicBoolean earlyExit = new AtomicBoolean(false);
		
		try {
			// fork=true — не UI-thread; cancelable=false — Process.waitFor Designer не отменяется через monitor
			new ProgressMonitorDialog(shell).run(true, false, monitor -> {
				monitor.beginTask("Помещение в хранилище", IProgressMonitor.UNKNOWN);
				
				monitor.subTask("Сравнение git-веток…");
				StorageUiPlugin.logInfo("Сравнение git-веток…");
				Map<String, List<DiffEntry>> allDiff = getBranchDiff(monitor);
				if (allDiff == null) {
					earlyExit.set(true);
					return;
				}
				if (allDiff.isEmpty()) {
					showWarningOnUi("Внимание",
							"Нет файлов конфигурации для помещения.\n"
							+ "Git-diff есть, но пути не распознаны (ожидается Project/src/... или src/... в корне проекта).");
					earlyExit.set(true);
					return;
				}
				
				int projectIndex = 0;
				int projectCount = allDiff.size();
				for (Map.Entry<String, List<DiffEntry>> entry : allDiff.entrySet()) {
					projectIndex++;
					String projectName = entry.getKey();
					List<DiffEntry> diff = entry.getValue();
					storageSettings = new Settings(projectName);
					
					String projectLabel = MessageFormat.format("Проект {0} из {1}: {2}",
							projectIndex, projectCount, projectName);
					monitor.subTask(projectLabel);
					StorageUiPlugin.logInfo(projectLabel);
					
					Path rootDirectory;
					try {
						rootDirectory = FileUtil.createTempDirectory("Zigr").toPath();
					} catch (IOException e) {
						StorageUiPlugin.logError(e.getMessage(), e);
						result.set(false);
						break;
					}
					
					try {
						if (pushBranchDiff(projectName, diff, rootDirectory, monitor)) {
							String message = MessageFormat.format(
									"Операция помещения в хранилище выполнена. ИБ={0}. Проект={1}",
									issueDescriptor.getInfobase().getName(), projectName);
							StorageUiPlugin.logInfo(message);
						} else {
							result.set(false);
						}
					} catch (IOException | CoreException | RuntimeExecutionException | InterruptedException e) {
						StorageUiPlugin.logError(e.getMessage(), e);
						result.set(false);
					}
					
					try {
						FileUtil.deleteRecursivelyWithRetries(rootDirectory);
					} catch (IOException e) {
						StorageUiPlugin.logError(e.getMessage(), e);
					}
					
					if (!result.get()) {
						break;
					}
					monitor.worked(1);
				}
				
				monitor.done();
			});
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			StorageUiPlugin.logError(cause.getMessage(), cause);
			result.set(false);
		} catch (InterruptedException e) {
			StorageUiPlugin.logError(e.getMessage(), e);
			result.set(false);
			Thread.currentThread().interrupt();
		}
		
		if (earlyExit.get()) {
			return null;
		}
		
		showResultOnUi(result.get());
		return null;
	}

	private boolean pushBranchDiff(String projectName, List<DiffEntry> diff, Path rootDirectory,
			IProgressMonitor monitor) throws IOException, CoreException, RuntimeExecutionException, InterruptedException {
		Path exportDirectory = FileUtil.createTempDirectory("Export", rootDirectory).toPath();
		
		setProgress(monitor, MessageFormat.format("Проект {0}: подключение к платформе / ИБ…", projectName));
		Designer designer = new Designer(issueDescriptor, projectName, rootDirectory);
		
		setProgress(monitor, MessageFormat.format("Проект {0}: закрытие сессии конфигуратора…", projectName));
		designer.closeDesignerSession(monitor);
		
		setProgress(monitor, MessageFormat.format("Проект {0}: определение объектов для захвата…", projectName));
		Map<QualifiedName, Boolean> lockObjects = getLockObjects(projectName, diff);
		if (lockObjects.isEmpty()) {
			IStatus status = StorageUiPlugin.createErrorStatus("Не удалось определить объекты для захвата");
			throw new CoreException(status);
		}
		
		setProgress(monitor, MessageFormat.format(
				"Проект {0}: захват объектов в хранилище… ({1})", projectName, lockObjects.size()));
		designer.lockObjects(lockObjects, monitor);
		
		setProgress(monitor, MessageFormat.format("Проект {0}: сравнение с конфигурацией БД…", projectName));
		if (!designer.isConfigurationSame(monitor)) {
			if (storageSettings.getPushIfConfigurationChanged()) {
				int answer = askContinueIfConfigurationChanged(projectName);
				if (answer == SWT.NO) {
					String message = MessageFormat.format(
							"Операция помещения в хранилище отменена пользователем. ИБ={0}. Проект={1}",
							issueDescriptor.getInfobase().getName(), projectName);
					StorageUiPlugin.logInfo(message);
					return false;
				}
			} else {
				String textMessage = textMessageIfConfigurationChanged(projectName);
				IStatus status = StorageUiPlugin.createErrorStatus(textMessage);
				throw new CoreException(status);
			}
		}
		
		setProgress(monitor, MessageFormat.format("Проект {0}: выгрузка объектов EDT в XML…", projectName));
		EObject[] topObjects = getTopObjects(projectName, diff);
		IExportOperation exportOperation = exportOperationFactory.createExportOperation
				(exportDirectory, designer.getVersion(), new IncrementalExportStrategy(), topObjects);
		SubMonitor exportMonitor = SubMonitor.convert(monitor, "Выгрузка объектов EDT в XML…", 100);
		IStatus status = exportOperation.run(exportMonitor);
		if (status.getSeverity() == 4) { 
			throw new CoreException(status);
		}
		
		setProgress(monitor, MessageFormat.format("Проект {0}: подготовка списка файлов к загрузке…", projectName));
		V8FileBuilder v8FileBuilder = new V8FileBuilder(exportDirectory, projectName);
		v8FileBuilder.setSourceFiles(diff);
		Set<Path> exportFiles = v8FileBuilder.getExportFiles();
		setProgress(monitor, MessageFormat.format(
				"Проект {0}: подготовка списка файлов… ({1})", projectName, exportFiles.size()));
		Path listFiles = rootDirectory.resolve("listFiles.txt");
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(listFiles.toString(), StandardCharsets.UTF_8))){
			for (Path exportFile : exportFiles) {
				writer.append(exportFile.toString()+System.lineSeparator());
			}
		} catch (IOException e) {
			throw e;
		}
		
		setProgress(monitor, MessageFormat.format("Проект {0}: загрузка XML в информационную базу…", projectName));
		designer.loadConfigurationFromXml(exportDirectory, listFiles, monitor);

		// После LoadCfg: Main≠DB. Статья п.8 — UpdateDBCfg, затем Поместить в хранилище.
		setProgress(monitor, MessageFormat.format("Проект {0}: обновление конфигурации БД…", projectName));
		designer.updateDatabaseConfiguration(monitor);

		String storeComment = buildStoreComment();
		setProgress(monitor, MessageFormat.format(
				"Проект {0}: помещение в хранилище… ({1})", projectName, lockObjects.size()));
		designer.storeObjects(lockObjects, storeComment, monitor);
		
		designer.dispose();
		return true;
	}

	private String buildStoreComment() {
		String branchName = "";
		try {
			if (issueDescriptor != null && issueDescriptor.getBranch() != null) {
				branchName = issueDescriptor.getBranch().getName();
			}
		} catch (Exception e) {
			StorageUiPlugin.logError("Не удалось получить имя ветки для комментария хранилища", e);
		}
		String shortName = Designer.sanitizeRepositoryComment(branchName);
		return "PluginEDT: " + shortName;
	}

	private int askContinueIfConfigurationChanged(String projectName) {
		AtomicReference<Integer> answer = new AtomicReference<>(SWT.NO);
		Display.getDefault().syncExec(() -> {
			if (shell == null || shell.isDisposed()) {
				return;
			}
			MessageBox dialog = new MessageBox(shell, SWT.ICON_WARNING | SWT.YES | SWT.NO);
			dialog.setText("Внимание!!!");
			String textMessage = textMessageIfConfigurationChanged(projectName)
					+ System.lineSeparator() + System.lineSeparator()
					+ "Все равно продолжить помещение?";
			dialog.setMessage(textMessage);
			answer.set(dialog.open());
		});
		return answer.get();
	}

	private void showWarningOnUi(String title, String message) {
		Display.getDefault().syncExec(() -> {
			if (shell != null && !shell.isDisposed()) {
				MessageDialog.openWarning(shell, title, message);
			}
		});
	}

	private void showErrorOnUi(String title, String message) {
		Display.getDefault().syncExec(() -> {
			if (shell != null && !shell.isDisposed()) {
				MessageDialog.openError(shell, title, message);
			}
		});
	}

	private void showResultOnUi(boolean success) {
		Display.getDefault().syncExec(() -> {
			if (shell == null || shell.isDisposed()) {
				return;
			}
			if (success) {
				MessageDialog.openInformation(shell, "Поместить в хранилище", "Операция успешно выполнена");
			} else {
				MessageDialog.openError(shell, "Поместить в хранилище",
						"Операция не выполнена (см. Журнал ошибок)");
			}
		});
	}

	private static void setProgress(IProgressMonitor monitor, String message) {
		if (monitor != null) {
			monitor.subTask(message);
		}
		StorageUiPlugin.logInfo(message);
	}

	private EObject[] getTopObjects(String projectName, List<DiffEntry> diff) {
		
		Set<EObject> topObjects = new HashSet<EObject>();
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		
		Set<String> sourceFiles = new HashSet<String>();
		for (DiffEntry entry : diff) {
			String sourceFile = entry.getNewPath();
			if (V8FileBuilder.isV8File(sourceFile)) {
				sourceFiles.add(sourceFile);
			}
		}
		
		Set<String> fqnStrings = new HashSet<String>();
		for (String sourceFile : sourceFiles) {
			QualifiedName fqn = qualifiedNameFilePathConverter.getFqn(toWorkspacePath(projectName, sourceFile));
			if (fqn == null) {
				continue;
			}
			int segmentCount = fqn.getSegmentCount();
			if ("Configuration".equals(fqn.getFirstSegment())) {
				fqnStrings.add("Configuration");
			} else if (segmentCount >= 2) {
				fqnStrings.add(fqn.skipLast(segmentCount - 2).toString());
			}
		}
		
		BmPlatform platform = modelManager.getBmPlatform();
		IBmNamespace ns = modelManager.getBmNamespace(project);
		IBmPlatformTransaction transaction = platform.beginReadOnlyTransaction(true);
		for (String fqnString : fqnStrings) {
			EObject topObject = (EObject) transaction.getTopObjectByFqn(ns, fqnString);
			if (topObject != null) {
				topObjects.add(topObject);
			}
		}
		transaction.commit();
		
		EObject[] result = new EObject[topObjects.size()];
		topObjects.toArray(result);
		return result;
	}

	private Map<QualifiedName, Boolean> getLockObjects(String projectName, List<DiffEntry> diff) {
		Map<QualifiedName, Boolean> result = new HashMap<QualifiedName, Boolean>();
		
		Set<String> sourceFiles = new HashSet<String>();
		for (DiffEntry entry : diff) {
			String oldPath = entry.getOldPath();
			String newPath = entry.getNewPath();
			String sourceFile;
			if (oldPath == DiffEntry.DEV_NULL) {
				sourceFile = newPath;
			}
			else {
				sourceFile = oldPath;
			}
			
			if (V8FileBuilder.isV8File(sourceFile)) {
				sourceFiles.add(sourceFile);
			}
		}
		
		for (String sourceFile : sourceFiles) {
			QualifiedName fqn = qualifiedNameFilePathConverter.getFqn(toWorkspacePath(projectName, sourceFile));
			if (fqn == null) {
				continue;
			}
			int segmentCount = fqn.getSegmentCount();
			String firstSegment = fqn.getFirstSegment();
			if ("Configuration".equals(firstSegment)) {
				result.put(fqn.skipLast(segmentCount - 1), false);
			} else if ("Subsystem".equals(firstSegment)) {
				int firstCount = 0;
				for (int i = 0; i < segmentCount; i = i + 2) {
					if ("Subsystem".equals(fqn.getSegment(i))) {
						firstCount = i + 2;
					} 
					else {
						break;
					}
				}
				if (firstCount > 0) {
					result.put(fqn.skipLast(segmentCount - firstCount), false);
				}
			} else if ("ExternalDataSource".equals(firstSegment)) {
				// сложная структура, редко используется
				result.put(fqn.skipLast(segmentCount - 2), true);
				
			} else if ("CalculationRegister".equals(firstSegment) && sourceFile.endsWith(".mdo")) {
				// Recalculation самостоятельный объект, но встроен в файл .mdo. Безусловный захват с подчиненными
				result.put(fqn.skipLast(segmentCount - 2), true);
				
			} else if (segmentCount >= 4 
					&& ("Form".equals(fqn.getSegment(2)) || "Template".equals(fqn.getSegment(2)))) {
				result.put(fqn.skipLast(segmentCount - 4), false);
				
			} else if (storageSettings.getExportMDWithMDO()) {
				if (sourceFile.endsWith(".mdo")) {
					// файлы .mdo захватываем с подчиненными, если включена настройка
					result.put(fqn.skipLast(segmentCount - 2), true);
				} else {
					if (result.get(fqn.skipLast(segmentCount - 2)) == null) { // возможно уже был добавлен .mdo
						result.put(fqn.skipLast(segmentCount - 2), false);
					}
				}
				
			} else {
				result.put(fqn.skipLast(segmentCount - 2), false);
			}
		}
		
		return result;
	}

	public Map<String, List<DiffEntry>> getBranchDiff(IProgressMonitor monitor) {
		
		Map<String, List<DiffEntry>> result = new HashMap<String, List<DiffEntry>>();
		
		List<DiffEntry> allDiff;
		Repository repository = issueDescriptor.getRepository();
		try (Git git = new Git(repository)) {
			String importBranch = issueDescriptor.getBranch().getName();
			String currentBranch = repository.getFullBranch();
			if (importBranch.equals(currentBranch)) {
				showWarningOnUi("Внимание", "Нельзя выбирать текущую ветку");
				return null;
			}
			if (monitor != null) {
				monitor.subTask("Сравнение git-веток…");
			}
			// the diff works on TreeIterators, we prepare two for the two branches
			AbstractTreeIterator oldTreeParser = prepareTreeParser(repository, importBranch);
			AbstractTreeIterator newTreeParser = prepareTreeParser(repository, currentBranch);
			// then the procelain diff-command returns a list of diff entries
			allDiff = git.diff().setOldTree(oldTreeParser).setNewTree(newTreeParser).call();
			// RenameDetector
			RenameDetector rd = new RenameDetector(repository);
			rd.addAll(allDiff);
			allDiff = rd.compute();
			if (allDiff.isEmpty()) {
				showWarningOnUi("Внимание", "Ветки не различаются");
				return null;
			}
		} catch (GitAPIException | IOException e) {
			StorageUiPlugin.logError(e.getMessage(), e);
			showErrorOnUi("Ошибка", "Не удалось определить различия веток (см. Журнал ошибок)");
			return null;
		}
		
		for (DiffEntry entry : allDiff) {
			String oldPath = entry.getOldPath();
			String newPath = entry.getNewPath();
			String sourceFile;
			if (newPath == DiffEntry.DEV_NULL) {
				sourceFile = oldPath;
			}
			else {
				sourceFile = newPath;
			}
			
			org.eclipse.core.runtime.IPath path = new org.eclipse.core.runtime.Path(sourceFile);
			String projectName;
			// Layout A: monorepo / parent git — ProjectName/src/...
			if (path.segmentCount() >= 2 && "src".equals(path.segment(1))) {
				projectName = path.segment(0);
			}
			// Layout B: .git inside EDT project — src/...
			else if (path.segmentCount() >= 1 && "src".equals(path.segment(0))) {
				projectName = resolveProjectName(repository);
				if (projectName == null || projectName.isEmpty()) {
					StorageUiPlugin.logError("Не удалось определить EDT-проект для git work tree: "
							+ repository.getWorkTree(), null);
					continue;
				}
			} else {
				continue;
			}
			
			List<DiffEntry> projectDiff = result.get(projectName);
			if (projectDiff == null) {
				projectDiff = new ArrayList<DiffEntry>();
				result.put(projectName, projectDiff);
			}
			projectDiff.add(entry);
		}
		
		if (result.isEmpty()) {
			showWarningOnUi("Внимание",
					"Различия веток найдены, но среди них нет файлов конфигурации (src/...).");
			return null;
		}
		
		int fileCount = allDiff.size();
		setProgress(monitor, MessageFormat.format(
				"Найдено изменений: {0} файлов, проектов: {1}", fileCount, result.size()));
		
		return result;
	}

	/**
	 * Git path → workspace-relative path for EDT FQN converter.
	 * Supports both Project/src/... and src/... (repo rooted at project).
	 */
	private String toWorkspacePath(String projectName, String gitPath) {
		if (gitPath == null || gitPath == DiffEntry.DEV_NULL) {
			return gitPath;
		}
		org.eclipse.core.runtime.IPath path = new org.eclipse.core.runtime.Path(gitPath);
		if (path.segmentCount() >= 1 && "src".equals(path.segment(0))) {
			return projectName + "/" + gitPath;
		}
		return gitPath;
	}

	private String resolveProjectName(Repository repository) {
		File workTree = repository.getWorkTree();
		if (workTree == null) {
			return null;
		}
		File canonicalWorkTree;
		try {
			canonicalWorkTree = workTree.getCanonicalFile();
		} catch (IOException e) {
			canonicalWorkTree = workTree;
		}
		for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			if (!project.isAccessible()) {
				continue;
			}
			org.eclipse.core.runtime.IPath location = project.getLocation();
			if (location == null) {
				continue;
			}
			try {
				if (location.toFile().getCanonicalFile().equals(canonicalWorkTree)) {
					return project.getName();
				}
			} catch (IOException e) {
				if (location.toFile().equals(workTree)) {
					return project.getName();
				}
			}
		}
		// Fallback: folder name usually equals EDT project name
		return workTree.getName();
	}

	private static AbstractTreeIterator prepareTreeParser(Repository repository, String ref) throws IOException {
		// from the commit we can build the tree which allows us to construct the TreeParser
		Ref head = repository.exactRef(ref);
		try (RevWalk walk = new RevWalk(repository)) {
			RevCommit commit = walk.parseCommit(head.getObjectId());
			RevTree tree = walk.parseTree(commit.getTree().getId());
			
			CanonicalTreeParser treeParser = new CanonicalTreeParser();
			try (ObjectReader reader = repository.newObjectReader()) {
				treeParser.reset(reader, tree.getId());
			}
			
			walk.dispose();
			return treeParser;
		}
	}

	private static final class IncrementalExportStrategy implements IExportStrategy {
		
		@Override
		public boolean exportSubordinatesObjects(EObject eObject) {
			return !(eObject instanceof Configuration);
		}

		@Override
		public boolean exportExternalProperties(EObject eObject) {
			return true;
		}

		public boolean exportUnknown() {
			return false;
		}

	}

	private String textMessageIfConfigurationChanged(String projectName) {
		String textMessage = "Проект:{0}. После захвата объектов в хранилище обнаружено отличие конфигурации от конфигурации БД!"
				+ System.lineSeparator() + System.lineSeparator()
				+ "Это могут быть изменения, полученные из хранилища во время захвата. Во избежание потерь этих изменений "
				+ "нужно переключиться на ветку хранилища, импортировать туда все изменения, переключиться на текущую ветку "
				+ "и влить изменения из ветки хранилища.";
		textMessage = MessageFormat.format(textMessage, projectName);
		return textMessage;
	}

	@Override
	public boolean isEnabled() {
		return true;
	}

	@Override
	public boolean isHandled() {
		return true;
	}

	@Override
	public void addHandlerListener(IHandlerListener handlerListener) {
		// Auto-generated method stub
	}

	@Override
	public void dispose() {
		// Auto-generated method stub
	}

	@Override
	public void removeHandlerListener(IHandlerListener handlerListener) {
		// Auto-generated method stub
	}
}
