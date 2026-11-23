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
        // Remember the current timestamp so the watcher does not immediately reload the same file.
        try {
            lastModified = Files.getLastModifiedTime(rulesFile).toMillis();
        } catch (Exception ignored) {
            lastModified = 0;
        }

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
        MockTransformer previous = currentTransformer;

        MockTransformer next = new MockTransformer(rules);
        if (currentTransformer != null) {
            instrumentation.removeTransformer(currentTransformer);
        }
        instrumentation.addTransformer(next, true);
        currentTransformer = next;

        Set<String> affected = new HashSet<>(previouslyAffected);
        // Both the old and the new rule set: removing an interface rule must also retransform
        // the implementations the previous rule set had touched.
        addInterfaceImplementors(previous, affected);
        affected.addAll(next.targets());
        addInterfaceImplementors(next, affected);
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

    /**
     * Rules created on an interface cannot instrument the (abstract) interface method. Add every
     * already-loaded concrete implementor to the retransform set, so Spring beans are mocked too.
     */
    private static void addInterfaceImplementors(MockTransformer transformer, Set<String> affected) {
        if (transformer == null || !transformer.hasInterfaceRules()) {
            return;
        }
        Class<?>[] loaded = instrumentation.getAllLoadedClasses();
        java.util.Map<String, Class<?>> byName = new java.util.HashMap<>();
        for (Class<?> candidate : loaded) {
            if (!candidate.isPrimitive() && !candidate.isArray()) {
                byName.put(candidate.getName(), candidate);
            }
        }
        for (String interfaceName : transformer.interfaceNames()) {
            Class<?> iface = byName.get(interfaceName);
            if (iface == null) {
                continue;
            }
            int matched = 0;
            for (Class<?> candidate : byName.values()) {
                if (candidate == iface || candidate.isInterface() || isProxyClass(candidate)) {
                    continue;
                }
                try {
                    if (iface.isAssignableFrom(candidate)) {
                        affected.add(candidate.getName());
                        matched++;
                    }
                } catch (Throwable ignored) {
                    // A class whose dependencies cannot be resolved is simply skipped.
                }
            }
            if (matched > 0 && transformer == currentTransformer) {
                System.out.println("[DebugMock] interface rule on " + interfaceName
                        + ": " + matched + " implementation(s)");
            }
        }
    }

    /** Spring CGLIB proxies / JDK dynamic proxies extend or delegate to the real bean. */
    private static boolean isProxyClass(Class<?> candidate) {
        if (java.lang.reflect.Proxy.isProxyClass(candidate)) {
            return true;
        }
        String name = candidate.getName();
        return name.contains("$$") || name.startsWith("com.sun.proxy.")
                || name.startsWith("jdk.proxy");
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
