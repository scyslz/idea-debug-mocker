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
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Persists mock rules to {@code <project>/.debugmock/rules.json}.
 *
 * <p>Reads are served from an in-memory cache that is validated against the file's modification
 * time and size (so external edits are still noticed) and every returned rule is a copy, so
 * callers can mutate what they get without corrupting the cache. Writes go through a temp file
 * and an atomic move, so the javaagent that watches the file never sees a half-written file, and
 * read-modify-write operations ({@link #update}) hold the lock for their whole duration.</p>
 */
public final class MockRuleStore {

    private static final Logger LOG = Logger.getInstance(MockRuleStore.class);
    private static final Type RULE_LIST_TYPE = new TypeToken<List<MockRule>>() { }.getType();

    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    /** Compact serializer used only to deep-copy rules. */
    private final Gson copier = new GsonBuilder().disableHtmlEscaping().create();
    private final Object lock = new Object();
    /** Parsed rules per rules file; guarded by {@link #lock}. */
    private final Map<Path, CachedRules> cache = new HashMap<>();

    private static final MockRuleStore INSTANCE = new MockRuleStore();

    private static final class CachedRules {
        final long modified;
        final long size;
        final List<MockRule> rules;

        CachedRules(long modified, long size, List<MockRule> rules) {
            this.modified = modified;
            this.size = size;
            this.rules = rules;
        }
    }

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
            return copyOf(cached(path));
        }
    }

    /** Replaces the whole rule set and notifies listeners. */
    public void save(@NotNull Project project, @NotNull List<MockRule> rules) {
        Path path = rulesPath(project);
        synchronized (lock) {
            write(path, rules);
        }
        notifyChanged();
    }

    /**
     * Atomic read-modify-write: {@code change} receives the current rules (copies) and returns
     * {@code true} when it modified them and they must be saved. No other store operation can
     * interleave, so concurrent edits (editor, tool window, PSI line sync) cannot overwrite
     * each other.
     */
    public void update(@NotNull Project project, @NotNull Predicate<List<MockRule>> change) {
        MockRuleLineSync.ensureInstalled(project);
        Path path = rulesPath(project);
        boolean saved;
        synchronized (lock) {
            List<MockRule> rules = copyOf(cached(path));
            saved = change.test(rules);
            if (saved) {
                write(path, rules);
            }
        }
        if (saved) {
            notifyChanged();
        }
    }

    /** All rules attached to a method (multiple conditional rules are allowed). */
    public List<MockRule> findAll(@NotNull Project project, String className, String methodName, String signature) {
        MockRuleLineSync.ensureInstalled(project);
        Path path = rulesPath(project);
        List<MockRule> result = new ArrayList<>();
        synchronized (lock) {
            for (MockRule rule : cached(path)) {
                if (rule.matches(className, methodName, signature)) {
                    result.add(copyOf(rule));
                }
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
        MockRuleLineSync.ensureInstalled(project);
        Path path = rulesPath(project);
        synchronized (lock) {
            for (MockRule rule : cached(path)) {
                if (id.equals(rule.getId())) {
                    return copyOf(rule);
                }
            }
        }
        return null;
    }

    /** Inserts or updates by stable id, so multiple rules per method can coexist. */
    public MockRule upsert(@NotNull Project project, @NotNull MockRule rule) {
        update(project, rules -> {
            for (int i = 0; i < rules.size(); i++) {
                if (rule.getId().equals(rules.get(i).getId())) {
                    rule.setCreatedAt(rules.get(i).getCreatedAt());
                    rule.setUpdatedAt(System.currentTimeMillis());
                    rules.set(i, rule);
                    return true;
                }
            }
            rule.setUpdatedAt(System.currentTimeMillis());
            rules.add(rule);
            return true;
        });
        return rule;
    }

    public void toggle(@NotNull Project project, @NotNull MockRule rule) {
        update(project, rules -> {
            for (MockRule r : rules) {
                if (rule.getId().equals(r.getId())) {
                    r.setEnabled(!r.isEnabled());
                    r.setUpdatedAt(System.currentTimeMillis());
                    rule.setEnabled(r.isEnabled());
                    break;
                }
            }
            return true;
        });
    }

    public void remove(@NotNull Project project, @NotNull MockRule rule) {
        update(project, rules -> {
            rules.removeIf(r -> rule.getId().equals(r.getId()));
            return true;
        });
    }

    public void removeAllForMethod(@NotNull Project project, String className, String methodName, String signature) {
        update(project, rules -> {
            rules.removeIf(r -> r.matches(className, methodName, signature));
            return true;
        });
    }

    /** Master switch: turns every rule in the project on or off. */
    public void setAllEnabled(@NotNull Project project, boolean enabled) {
        update(project, rules -> {
            if (rules.isEmpty()) {
                return false;
            }
            for (MockRule rule : rules) {
                rule.setEnabled(enabled);
                rule.setUpdatedAt(System.currentTimeMillis());
            }
            return true;
        });
    }

    public void setEnabledForMethod(@NotNull Project project, String className, String methodName,
                                    String signature, boolean enabled) {
        update(project, rules -> {
            for (MockRule r : rules) {
                if (r.matches(className, methodName, signature)) {
                    r.setEnabled(enabled);
                    r.setUpdatedAt(System.currentTimeMillis());
                }
            }
            return true;
        });
    }

    // ---- internals (all callers hold {@link #lock}) ----------------------------------------

    /** The shared, parsed rules for a file; callers must copy before handing them out. */
    private List<MockRule> cached(Path path) {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(path, BasicFileAttributes.class);
        } catch (IOException e) {
            cache.remove(path);
            return Collections.emptyList();
        }
        if (!attrs.isRegularFile()) {
            cache.remove(path);
            return Collections.emptyList();
        }
        long modified = attrs.lastModifiedTime().toMillis();
        CachedRules hit = cache.get(path);
        if (hit != null && hit.modified == modified && hit.size == attrs.size()) {
            return hit.rules;
        }
        List<MockRule> parsed = readFromDisk(path);
        cache.put(path, new CachedRules(modified, attrs.size(), parsed));
        return parsed;
    }

    private List<MockRule> readFromDisk(Path path) {
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

    /**
     * Writes through a temp file + atomic move: the javaagent polls this file and must never see
     * it truncated or half-written (that used to look like "all rules removed").
     */
    private void write(Path path, List<MockRule> rules) {
        Path tmp = null;
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(tmp, gson.toJson(rules), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFailed) {
                // Some file systems cannot move atomically; a plain replace is still better than
                // rewriting the live file in place.
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
        } catch (IOException e) {
            LOG.error("Cannot write mock rules to " + path, e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
        // Re-stat so the cache matches exactly what is now on disk.
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            cache.put(path, new CachedRules(attrs.lastModifiedTime().toMillis(), attrs.size(),
                    copyOf(rules)));
        } catch (IOException e) {
            cache.remove(path);
        }
    }

    private List<MockRule> copyOf(List<MockRule> rules) {
        List<MockRule> copy = new ArrayList<>(rules.size());
        for (MockRule rule : rules) {
            copy.add(copyOf(rule));
        }
        return copy;
    }

    private MockRule copyOf(MockRule rule) {
        return copier.fromJson(copier.toJson(rule), MockRule.class);
    }

    private void notifyChanged() {
        ApplicationManager.getApplication().getMessageBus()
                .syncPublisher(MockRuleListener.TOPIC).rulesChanged();
    }
}
