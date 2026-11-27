package com.debugmock.agent;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Loads rules.json (same schema the IDE plugin writes). */
public final class RuleLoader {

    private RuleLoader() {
    }

    /** Loads the rules; an absent or unreadable file yields an empty list (startup behaviour). */
    public static List<MockRule> load(Path path) {
        List<MockRule> rules = read(path);
        return rules == null ? Collections.<MockRule>emptyList() : rules;
    }

    /**
     * Reads the rules, or returns {@code null} when the file exists but cannot be used right now
     * (blank, half-written, invalid JSON). The hot-reload watcher keeps the previous rules in that
     * case, instead of treating a transient bad read as "the user removed every rule".
     */
    public static List<MockRule> read(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return Collections.emptyList();
        }
        try {
            String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            if (json.trim().isEmpty()) {
                return null;
            }
            MockRule[] rules = new Gson().fromJson(json, MockRule[].class);
            if (rules == null) {
                return Collections.emptyList();
            }
            List<MockRule> result = new ArrayList<MockRule>(rules.length);
            for (MockRule rule : rules) {
                if (rule != null) {
                    result.add(rule);
                }
            }
            return result;
        } catch (IOException | RuntimeException e) {
            System.err.println("[DebugMock] cannot read rules from " + path + ": " + e);
            return null;
        }
    }
}
