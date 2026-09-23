package com.aicompany.backend.agent.routing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The two lexical rules the router ranks by, pinned where no fixture happens to reach them. */
class AgentRouterTermsTest {

    @Test
    void termsAreLowerCasedWordsOfThreeLettersOrMoreWithoutStopwords() {
        assertThat(AgentRouter.terms("Add an INDEX to the PostgreSQL db, for the runs-table"))
                .containsExactly("index", "postgresql", "runs", "table");
    }

    @Test
    void termsKeepAccentedWords() {
        assertThat(AgentRouter.terms("Progettazione dell'architettura")).contains("progettazione", "architettura");
    }

    @Test
    void relatedMeansEqualOrALongEnoughPrefix() {
        assertThat(AgentRouter.related("database", "database")).isTrue();
        assertThat(AgentRouter.related("databases", "database")).isTrue();
        assertThat(AgentRouter.related("model", "modeling")).isTrue();
        assertThat(AgentRouter.related("api", "apiary")).as("too short to be a prefix that means anything").isFalse();
        assertThat(AgentRouter.related("react", "redux")).isFalse();
    }
}
