package com.debugmock.agent;

import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Java agent entry point. Rules file is given via -Ddebugmock.rules or agent arguments. */
public final class MockAgent {

    public static final String RULES_PROPERTY = "debugmock.rules";

    private static volatile Instrumentation instrumentation;
    private static volatile Path rulesFile;
    private static volatile MockTransformer currentTransformer;
    private static volatile long lastModified;

    private MockAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        String rulesPath = System.getProperty(RULES_PROPERTY);
        if (rulesPath == null || rulesPath.trim().isEmpty()) {
            rulesPath = args;
        }
        if (rulesPath == null || rulesPath.trim().isEmpty()) {
            System.err.println("[DebugMock] agent started without a rules file; no mocks applied. "
                    + "Pass -D" + RULES_PROPERTY + "=<path>/rules.json");
            return;
        }

        instrumentation = inst;
        rulesFile = Paths.get(rulesPath);
        applyRules(RuleLoader.load(rulesFile));

        // Hot reload: react to rules created/edited while the JVM is running, so a rule takes
        // effect immediately instead of only on the next launch.
        Thread watcher = new Thread(MockAgent::watchRules, "debugmock-rules-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static void applyRules(List<MockRule> rules) {
        // A rule may have changed its source; drop classes compiled from the old rules.
        MockRuntime.clearCompiledCache();
        MockRuntime.registerSources(rules);
        Set<String> previouslyAffected = currentTransformer == null
                ? Collections.<String>emptySet() : currentTransformer.targets();

        MockTransformer next = new MockTransformer(rules);
        if (currentTransformer != null) {
            instrumentation.removeTransformer(currentTransformer);
        }
        instrumentation.addTransformer(next, true);
        currentTransformer = next;

        Set<String> affected = new HashSet<>(previouslyAffected);
        affected.addAll(next.targets());
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (!loaded.isPrimitive() && !loaded.isArray()
                    && affected.contains(loaded.getName())) {
                try {
                    instrumentation.retransformClasses(loaded);
                } catch (Throwable ignored) {
                    // Some classes cannot be retransformed; leave them untouched.
                }
            }
        }
        System.out.println("[DebugMock] agent active, " + rules.size() + " rule(s) from " + rulesFile);
    }

    private static void watchRules() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(1000);
                if (rulesFile == null || !Files.isRegularFile(rulesFile)) {
                    continue;
                }
                long modified = Files.getLastModifiedTime(rulesFile).toMillis();
                if (modified != lastModified) {
                    lastModified = modified;
                    applyRules(RuleLoader.load(rulesFile));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                System.err.println("[DebugMock] rules watcher error: " + t);
            }
        }
    }
}
