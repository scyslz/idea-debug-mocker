package com.debugmock.agent;

import com.intellij.openapi.application.PathManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/** Extracts the bundled javaagent jar to a stable local path for -javaagent injection. */
public final class AgentLocator {

    private static final String RESOURCE = "/agent/debugmock-agent.jar";

    private AgentLocator() {
    }

    /** Always re-extracts the bundled jar so an updated plugin never leaves a stale agent behind. */
    public static synchronized String agentJarPath() {
        // Dev override: runIde points this at build/agent/debugmock-agent.jar. hotSwap only
        // redefines plugin classes, not resources, so without this the IDE would keep injecting an
        // agent jar built before the latest agent change (and EXECUTE rules would not run).
        String override = System.getProperty("debugmock.agent.jar");
        if (override != null && !override.trim().isEmpty()
                && Files.isRegularFile(Paths.get(override))) {
            return Paths.get(override).toAbsolutePath().toString();
        }
        try (InputStream in = AgentLocator.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("DebugMock agent jar is not bundled in the plugin");
            }
            Path dir = Paths.get(PathManager.getTempPath(), "debugmock");
            Files.createDirectories(dir);
            Path jar = dir.resolve("debugmock-agent.jar");
            Files.copy(in, jar, StandardCopyOption.REPLACE_EXISTING);
            return jar.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot extract DebugMock agent jar", e);
        }
    }

    /** JVM options string, useful for remote JVMs that the IDE cannot configure automatically. */
    public static String agentJvmOptions(String rulesPath) {
        String agent = quote(agentJarPath());
        String rules = quote(rulesPath);
        return "-javaagent:" + agent + " -Ddebugmock.rules=" + rules;
    }

    private static String quote(String value) {
        return value.contains(" ") ? "\"" + value + "\"" : value;
    }
}
