package com.debugmock.actions;

import com.debugmock.model.MockRule;
import com.debugmock.psi.MethodInfo;
import com.debugmock.psi.MethodResolver;
import com.debugmock.store.MockRuleStore;
import com.debugmock.ui.RuleUiService;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/** Editor entry point: edit the mock rule attached to the current method. */
public class EditMockRuleAction extends AnAction {

    @Override
    @NotNull
    public com.intellij.openapi.actionSystem.ActionUpdateThread getActionUpdateThread() {
        // update() resolves PSI and reads the rules store: keep that off the UI thread.
        return com.intellij.openapi.actionSystem.ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        MethodInfo info = MethodResolver.resolve(event);
        if (project == null || info == null) {
            return;
        }
        MockRule rule = MockRuleStore.getInstance().find(
                project, info.getClassName(), info.getMethodName(), info.getSignature());
        if (rule != null) {
            RuleUiService.createOrEdit(project, info, rule);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        MethodInfo info = MethodResolver.resolve(event);
        boolean visible = false;
        if (info != null && event.getProject() != null) {
            visible = MockRuleStore.getInstance().find(
                    event.getProject(), info.getClassName(), info.getMethodName(), info.getSignature()) != null;
        }
        event.getPresentation().setEnabledAndVisible(visible);
    }
}
