package com.debugmock.store;

import com.debugmock.model.MockRule;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Persists mock rules to {@code <project>/.debugmock/rules.json}.
 */
public final class MockRuleStore {

    private static final Logger LOG = Logger.getInstance(MockRuleStore.class);
    private static final Type RULE_LIST_TYPE = new TypeToken<List<MockRule>>() { }.getType();

    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Object lock = new Object();

    private static final MockRuleStore INSTANCE = new MockRuleStore();

    private MockRuleStore() {
    }

    public static MockRuleStore getInstance() {
        return INSTANCE;
    }

    /** Returns the rules.json path for the given project. */
    public Path rulesPath(@NotNull Project project) {
        VirtualFile base = ProjectUtil.guessProjectDir(project);
        String basePath = base != null ? base.getPath() : project.getBasePath();
        if (basePath == null) {
            basePath = System.getProperty("user.home");
        }
        return Paths.get(basePath, ".debugmock", "rules.json");
    }

    /** Loads all rules. Never throws; returns an empty list when the file is absent or invalid. */
    public List<MockRule> load(@NotNull Project project) {
        // Install once per project: rules must follow their code while the source is edited.
        MockRuleLineSync.ensureInstalled(project);
        Path path = rulesPath(project);
        synchronized (lock) {
            if (!Files.isRegularFile(path)) {
                return new ArrayList<>();
            }
            try {
                String json = Files.readString(path, StandardCharsets.UTF_8);
                if (json.isBlank()) {
                    return new ArrayList<>();
                }
                List<MockRule> rules = gson.fromJson(json, RULE_LIST_TYPE);
                if (rules == null) {
                    return new ArrayList<>();
                }
                rules.removeIf(Objects::isNull);
                return rules;
            } catch (IOException | RuntimeException e) {
                LOG.warn("Cannot read mock rules from " + path, e);
                return new ArrayList<>();
            }
        }
    }

    /** Replaces the whole rule set and notifies listeners. */
    public void save(@NotNull Project project, @NotNull List<MockRule> rules) {
        Path path = rulesPath(project);
        synchronized (lock) {
            try {
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(path, gson.toJson(rules), StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOG.error("Cannot write mock rules to " + path, e);
            }
        }
        notifyChanged();
    }

    /** All rules attached to a method (multiple conditional rules are allowed). */
    public List<MockRule> findAll(@NotNull Project project, String className, String methodName, String signature) {
        List<MockRule> result = new ArrayList<>();
        for (MockRule rule : load(project)) {
            if (rule.matches(className, methodName, signature)) {
                result.add(rule);
            }
        }
        return result;
    }

    /** First rule for a method, kept for the legacy single-rule quick actions. */
    public MockRule find(@NotNull Project project, String className, String methodName, String signature) {
        List<MockRule> rules = findAll(project, className, methodName, signature);
        return rules.isEmpty() ? null : rules.get(0);
    }

    public MockRule findById(@NotNull Project project, String id) {
        if (id == null) {
            return null;
        }
        for (MockRule rule : load(project)) {
            if (id.equals(rule.getId())) {
                return rule;
            }
        }
        return null;
    }

    /** Inserts or updates by stable id, so multiple rules per method can coexist. */
    public MockRule upsert(@NotNull Project project, @NotNull MockRule rule) {
        List<MockRule> rules = load(project);
        boolean updated = false;
        for (int i = 0; i < rules.size(); i++) {
            if (rule.getId().equals(rules.get(i).getId())) {
                rule.setCreatedAt(rules.get(i).getCreatedAt());
                rule.setUpdatedAt(System.currentTimeMillis());
                rules.set(i, rule);
                updated = true;
                break;
            }
        }
        if (!updated) {
            rule.setUpdatedAt(System.currentTimeMillis());
            rules.add(rule);
        }
        save(project, rules);
        return rule;
    }

    public void toggle(@NotNull Project project, @NotNull MockRule rule) {
        List<MockRule> rules = load(project);
        for (MockRule r : rules) {
            if (rule.getId().equals(r.getId())) {
                r.setEnabled(!r.isEnabled());
                r.setUpdatedAt(System.currentTimeMillis());
                rule.setEnabled(r.isEnabled());
                break;
            }
        }
        save(project, rules);
    }

    public void remove(@NotNull Project project, @NotNull MockRule rule) {
        List<MockRule> rules = load(project);
        rules.removeIf(r -> rule.getId().equals(r.getId()));
        save(project, rules);
    }

    public void removeAllForMethod(@NotNull Project project, String className, String methodName, String signature) {
        List<MockRule> rules = load(project);
        rules.removeIf(r -> r.matches(className, methodName, signature));
        save(project, rules);
    }

    /** Master switch: turns every rule in the project on or off. */
    public void setAllEnabled(@NotNull Project project, boolean enabled) {
        List<MockRule> rules = load(project);
        if (rules.isEmpty()) {
            return;
        }
        for (MockRule rule : rules) {
            rule.setEnabled(enabled);
            rule.setUpdatedAt(System.currentTimeMillis());
        }
        save(project, rules);
    }

    public void setEnabledForMethod(@NotNull Project project, String className, String methodName,
                                    String signature, boolean enabled) {
        List<MockRule> rules = load(project);
        for (MockRule r : rules) {
            if (r.matches(className, methodName, signature)) {
                r.setEnabled(enabled);
                r.setUpdatedAt(System.currentTimeMillis());
            }
        }
        save(project, rules);
    }

    private void notifyChanged() {
        ApplicationManager.getApplication().getMessageBus()
                .syncPublisher(MockRuleListener.TOPIC).rulesChanged();
    }
}
