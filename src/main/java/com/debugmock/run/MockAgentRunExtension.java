package com.debugmock.run;

import com.debugmock.agent.AgentLocator;
import com.debugmock.store.MockRuleStore;
import com.intellij.execution.RunConfigurationExtension;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.RunConfigurationBase;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Adds the DebugMock javaagent to every Java Run/Debug configuration of a project that has
 * mock rules. Registered via the {@code com.intellij.runConfigurationExtension} extension point.
 */
public class MockAgentRunExtension extends RunConfigurationExtension {

    private static final Logger LOG = Logger.getInstance(MockAgentRunExtension.class);

    @Override
    public <T extends RunConfigurationBase<?>> void updateJavaParameters(
            T configuration, JavaParameters parameters, RunnerSettings runnerSettings) {
        Project project = configuration.getProject();
        if (project == null) {
            return;
        }
        Path rulesFile = MockRuleStore.getInstance().rulesPath(project).toAbsolutePath();
        // The agent hot-reloads rules.json, so it must also be present when the project uses
        // DebugMock but has no rule yet (or deleted them all): a rule added while the app is
        // running then takes effect immediately instead of only after a restart.
        Path rulesDir = rulesFile.getParent();
        if (MockRuleStore.getInstance().load(project).isEmpty()
                && (rulesDir == null || !Files.isDirectory(rulesDir))) {
            return;
        }
        try {
            String rulesPath = rulesFile.toString();
            String agentJar = AgentLocator.agentJarPath();
            parameters.getVMParametersList().add("-javaagent:" + agentJar);
            // add() appends ONE argument, so the path must not be quoted here: with literal quotes
            // a project path containing a space produced an invalid rules path in the app JVM.
            parameters.getVMParametersList().add("-Ddebugmock.rules=" + rulesPath);
            LOG.info("[DebugMock] injected agent into " + configuration.getName()
                    + " (rules=" + rulesPath + ")");
        } catch (RuntimeException e) {
            LOG.error("[DebugMock] failed to inject agent into " + configuration.getName(), e);
        }
    }

    @Override
    public boolean isApplicableFor(RunConfigurationBase<?> configuration) {
        return true;
    }
}
