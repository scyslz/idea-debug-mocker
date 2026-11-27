package com.debugmock.actions;

import com.debugmock.model.MockRule;
import com.debugmock.psi.MethodInfo;
import com.debugmock.psi.MethodResolver;
import com.debugmock.store.MockRuleStore;
import com.debugmock.util.NotificationUtil;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/** Editor entry point: enable / disable the mock rule attached to the current method. */
public class ToggleMockRuleAction extends AnAction {

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
        if (rule == null) {
            return;
        }
        MockRuleStore.getInstance().toggle(project, rule);
        NotificationUtil.info(project, "DebugMock",
                "Mock rule " + (rule.isEnabled() ? "enabled" : "disabled") + " for "
                        + info.getClassName() + "#" + info.getMethodName());
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        MethodInfo info = MethodResolver.resolve(event);
        MockRule rule = null;
        if (info != null && event.getProject() != null) {
            rule = MockRuleStore.getInstance().find(
                    event.getProject(), info.getClassName(), info.getMethodName(), info.getSignature());
        }
        boolean visible = rule != null;
        event.getPresentation().setEnabledAndVisible(visible);
        if (visible) {
            event.getPresentation().setText(rule.isEnabled() ? "Disable Mock" : "Enable Mock");
        }
    }
}
