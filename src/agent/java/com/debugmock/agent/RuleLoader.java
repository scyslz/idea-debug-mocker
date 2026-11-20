package com.debugmock.agent;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Loads rules.json (same schema the IDE plugin writes). */
public final class RuleLoader {

    private RuleLoader() {
    }

    public static List<MockRule> load(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return Collections.emptyList();
        }
        try {
            String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            if (json.trim().isEmpty()) {
                return Collections.emptyList();
            }
            MockRule[] rules = new Gson().fromJson(json, MockRule[].class);
            return rules == null ? Collections.emptyList() : Arrays.asList(rules);
        } catch (IOException | RuntimeException e) {
            System.err.println("[DebugMock] cannot read rules from " + path + ": " + e);
            return Collections.emptyList();
        }
    }
}
