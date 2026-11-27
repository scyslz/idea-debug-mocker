package com.debugmock.actions;

import com.debugmock.agent.AgentLocator;
import com.debugmock.store.MockRuleStore;
import com.debugmock.util.NotificationUtil;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.awt.datatransfer.StringSelection;

/**
 * Copies the JVM options needed to attach the agent to a remote JVM (the IDE cannot modify a
 * remote process command line automatically).
 */
public class CopyAgentOptionsAction extends AnAction {

    @Override
    @NotNull
    public com.intellij.openapi.actionSystem.ActionUpdateThread getActionUpdateThread() {
        // update() resolves PSI and reads the rules store: keep that off the UI thread.
        return com.intellij.openapi.actionSystem.ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }
        String rulesPath = MockRuleStore.getInstance().rulesPath(project).toAbsolutePath().toString();
        String options = AgentLocator.agentJvmOptions(rulesPath);
        CopyPasteManager.getInstance().setContents(new StringSelection(options));
        NotificationUtil.info(project, "DebugMock",
                "Copied agent JVM options. Add them to the remote JVM command line; "
                        + "copy the agent jar and rules file to the remote machine first.");
    }
}
