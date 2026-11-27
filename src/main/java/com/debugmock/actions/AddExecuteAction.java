package com.debugmock.actions;

import com.debugmock.model.MockRule;
import com.debugmock.psi.LineTargets;
import com.debugmock.psi.MethodInfo;
import com.debugmock.psi.MethodResolver;
import com.debugmock.store.MockRuleStore;
import com.debugmock.ui.AddMockRuleDialog;
import com.debugmock.util.NotificationUtil;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Adds an EXECUTE rule at the exact source line of the caret. The caret line is normalised to the
 * enclosing statement start (a rule can only hook a statement), and the line is captured
 * automatically instead of typed manually.
 */
public class AddExecuteAction extends AnAction {

    @Override
    @NotNull
    public com.intellij.openapi.actionSystem.ActionUpdateThread getActionUpdateThread() {
        // update() resolves PSI and reads the rules store: keep that off the UI thread.
        return com.intellij.openapi.actionSystem.ActionUpdateThread.BGT;
    }

    public AddExecuteAction() {
        // Text/description come from plugin.xml so the same class can be registered both in the
        // DebugMock submenu and as a top-level "Add DebugMock Rule at This Line" action.
        super();
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        MethodInfo info = MethodResolver.resolve(event);
        if (project == null || info == null) {
            return;
        }
        Editor editor = event.getData(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR);
        int line = editor == null ? info.getLineNumber()
                : LineTargets.statementLineAtCaret(project, editor, info);
        if (line <= 0) {
            NotificationUtil.warn(project, "DebugMock",
                    "Place the caret on a statement inside the method body.");
            return;
        }
        MockRule rule = AddMockRuleDialog.showExecute(project, info, line);
        if (rule != null) {
            MockRuleStore.getInstance().upsert(project, rule);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(MethodResolver.resolve(event) != null);
    }
}
