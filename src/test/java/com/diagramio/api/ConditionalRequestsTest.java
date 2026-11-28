package com.diagramio.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConditionalRequestsTest {

    private static final String TAG = ConditionalRequests.diagramEtag(3);

    @Test
    void tagsAreWeakAndCarryTheVersion() {
        assertThat(TAG).isEqualTo("W/\"3\"");
        assertThat(ConditionalRequests.sharedDiagramEtag(3, "view")).isEqualTo("W/\"3-view\"");
    }

    @Test
    void weakComparisonIgnoresTheWeakPrefixOnEitherSide() {
        assertThat(ConditionalRequests.matches("W/\"3\"", TAG)).isTrue();
        assertThat(ConditionalRequests.matches("\"3\"", TAG)).isTrue();
        assertThat(ConditionalRequests.matches("W/\"3\"", "\"3\"")).isTrue();
    }

    @Test
    void anyTagOfAListOrTheWildcardMatches() {
        assertThat(ConditionalRequests.matches("W/\"1\", W/\"2\" ,W/\"3\"", TAG)).isTrue();
        assertThat(ConditionalRequests.matches("*", TAG)).isTrue();
        assertThat(ConditionalRequests.matches("W/\"1\", W/\"2\"", TAG)).isFalse();
    }

    @Test
    void otherVersionsAndMalformedHeadersDoNotMatch() {
        assertThat(ConditionalRequests.matches("W/\"30\"", TAG)).isFalse();
        assertThat(ConditionalRequests.matches("W/\"3-view\"", TAG)).isFalse();
        assertThat(ConditionalRequests.matches("3", TAG)).isFalse();
        assertThat(ConditionalRequests.matches("W/3", TAG)).isFalse();
        assertThat(ConditionalRequests.matches("\"", TAG)).isFalse();
        assertThat(ConditionalRequests.matches("", TAG)).isFalse();
        assertThat(ConditionalRequests.matches(null, TAG)).isFalse();
    }
}
