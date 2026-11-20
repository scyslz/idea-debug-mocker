# Local smoke test

This is a tiny Java project with 4 mock rules already written to `.debugmock/rules.json`.

## Option 1 — standalone (guaranteed, no IDE)

```bash
chmod +x run-sample.sh && ./run-sample.sh
```

Expected: the first run prints real values; the second prints mocked values
(`mock-name-from-rule`, `99`, `true`) and does **not** print `REAL log()`.

## Option 2 — through the IDE plugin

1. From the plugin project root: `./gradlew runIde` (opens a sandbox IDEA 2026.3 with the plugin).
   - If it shows the first-run agreement dialog, accept it.
2. In the sandbox IDEA: `File → Open` and select **this `sample-app` folder** as a project.
3. Open `src/demo/UserService.java`. The rules are already in `.debugmock/rules.json`; you can
   also right-click a method → `DebugMock → Add Mock Rule` to create one from scratch.
4. Open `src/demo/Main.java` and click the green **Run** arrow on `main` (choose the Application
   configuration, not a Gradle/Maven task).
5. The Run console should print `[DebugMock] agent active, 4 rule(s) from ...` and the mocked
   values. `idea.log` will also contain `[DebugMock] injected agent into ...`.

If step 5 does not appear, check `idea.log` (`Help → Show Log in Explorer`) for a line starting
with `[DebugMock]`. That tells you whether the injection happened.

> Note: the plugin injects the agent into **Java Application / JUnit** run configurations. If you
> launch the app through a Gradle/Maven run task or from a terminal, the IDE cannot inject it —
> add the JVM options yourself using `DebugMock → Copy Agent JVM Options (Remote JVM)`.
