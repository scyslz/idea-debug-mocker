package com.debugmock.actions;

import com.debugmock.model.MockRule;
import com.debugmock.psi.MethodInfo;
import com.debugmock.psi.MethodResolver;
import com.debugmock.store.MockRuleStore;
import com.debugmock.util.NotificationUtil;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

/** Editor entry point: remove the mock rule attached to the current method. */
public class RemoveMockRuleAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        MethodInfo info = MethodResolver.resolve(event);
        if (project == null || info == null) {
            return;
        }
        MockRule rule = MockRuleStore.getInstance().find(
                project, info.getClassName(), info.getMethodName(), info.getSignature());
        if (rule == null) {
            return;
        }
        int answer = Messages.showYesNoDialog(
                project,
                "Remove the mock rule for " + info.getClassName() + "#" + info.getMethodName() + "()?",
                "DebugMock", "Remove", "Cancel", Messages.getQuestionIcon());
        if (answer != Messages.YES) {
            return;
        }
        MockRuleStore.getInstance().remove(project, rule);
        NotificationUtil.info(project, "DebugMock",
                "Removed mock rule for " + info.getClassName() + "#" + info.getMethodName());
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
