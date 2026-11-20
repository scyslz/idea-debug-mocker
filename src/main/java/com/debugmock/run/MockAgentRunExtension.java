package com.debugmock.run;

import com.debugmock.agent.AgentLocator;
import com.debugmock.store.MockRuleStore;
import com.intellij.execution.RunConfigurationExtension;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.RunConfigurationBase;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

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
        if (project == null || MockRuleStore.getInstance().load(project).isEmpty()) {
            return;
        }
        try {
            String rulesPath = MockRuleStore.getInstance().rulesPath(project).toAbsolutePath().toString();
            String agentJar = AgentLocator.agentJarPath();
            parameters.getVMParametersList().add("-javaagent:" + agentJar);
            parameters.getVMParametersList().add("-Ddebugmock.rules=" + quote(rulesPath));
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

    private static String quote(String value) {
        return value.contains(" ") ? "\"" + value + "\"" : value;
    }
}
