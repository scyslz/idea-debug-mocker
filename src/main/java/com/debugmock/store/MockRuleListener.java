package com.debugmock.store;

import com.intellij.util.messages.Topic;

/**
 * Notified whenever the set of mock rules changes, so the tool window can refresh.
 */
public interface MockRuleListener {

    Topic<MockRuleListener> TOPIC = Topic.create("DebugMock.RulesChanged", MockRuleListener.class);

    void rulesChanged();
}
