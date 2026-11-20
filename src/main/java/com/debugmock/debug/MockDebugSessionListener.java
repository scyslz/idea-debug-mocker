package com.debugmock.debug;

import com.intellij.debugger.impl.DebuggerManagerListener;
import com.intellij.debugger.impl.DebuggerSession;

/**
 * Listens for local and remote debug sessions attaching and registers breakpoints for all
 * enabled mock rules. Registered via {@code <projectListeners>} in plugin.xml.
 */
public final class MockDebugSessionListener implements DebuggerManagerListener {

    @Override
    public void sessionAttached(DebuggerSession session) {
        MockBreakpointRegistrar.registerAll(session.getProject());
    }
}
