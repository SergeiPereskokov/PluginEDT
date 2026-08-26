package dev.zigr.dt.team.ui.storage;

import java.io.IOException;
import java.util.List;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IHandler;
import org.eclipse.core.commands.IHandlerListener;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import com._1c.g5.v8.dt.team.git.infobases.IGitBranchIssueDescriptor;

public class ImportHandler implements IHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		Shell shell = HandlerUtil.getActiveShell(event);

		MessageBox confirm = new MessageBox(shell, SWT.ICON_QUESTION | SWT.YES | SWT.NO);
		confirm.setText("Получить из хранилища");
		confirm.setMessage("Получить последнюю версию из хранилища в ИБ и импортировать изменения в EDT?");
		if (confirm.open() == SWT.NO) {
			return null;
		}

		IStructuredSelection selection = HandlerUtil.getCurrentStructuredSelection(event);
		Object firstElement = selection.getFirstElement();
		IGitBranchIssueDescriptor issueDescriptor = Adapters.adapt(firstElement, IGitBranchIssueDescriptor.class);
		if (issueDescriptor == null) {
			MessageDialog.openError(shell, "Получить из хранилища",
					"Не удалось определить дескриптор ветки хранилища");
			return null;
		}

		OperationLogger logger;
		try {
			logger = OperationLogger.create();
		} catch (IOException e) {
			StorageUiPlugin.logError(e.getMessage(), e);
			MessageDialog.openError(shell, "Ошибка", "Не удалось создать журнал операции");
			return null;
		}
		logger.step("Старт операции получения из хранилища");
		logger.detail("ИБ: " + issueDescriptor.getInfobase().getName());
		logger.detail("Ветка хранилища: " + issueDescriptor.getBranch().getName());

		List<IProject> projects = StoragePullService.getConfiguredProjects(issueDescriptor, logger);
		if (projects.isEmpty()) {
			MessageDialog.openWarning(shell, "Получить из хранилища",
					"Не найдены проекты текущего репозитория с заполненным адресом хранилища.\nЖурнал: "
							+ logger.getLogFile());
			return null;
		}

		OperationLogDialog dialog = new OperationLogDialog(shell, "Получить из хранилища", logger,
				monitor -> StoragePullService.pullAllProjects(issueDescriptor, projects, logger, monitor));
		dialog.open();

		return null;
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
	}

	@Override
	public void dispose() {
	}

	@Override
	public void removeHandlerListener(IHandlerListener handlerListener) {
	}
}
