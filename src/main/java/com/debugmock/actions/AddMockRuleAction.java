package com.debugmock.actions;

import com.debugmock.model.MockRule;
import com.debugmock.psi.MethodInfo;
import com.debugmock.psi.MethodResolver;
import com.debugmock.store.MockRuleStore;
import com.debugmock.ui.RuleUiService;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

/**
 * Editor entry point: create a mock rule for the method at the caret / selection.
 */
public class AddMockRuleAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        MethodInfo info = MethodResolver.resolve(event);
        if (project == null || info == null) {
            return;
        }

        RuleUiService.createOrEdit(project, info, null);
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        MethodInfo info = MethodResolver.resolve(event);
        if (info == null || event.getProject() == null) {
            event.getPresentation().setEnabledAndVisible(false);
            return;
        }
        event.getPresentation().setEnabledAndVisible(true);
    }
}
