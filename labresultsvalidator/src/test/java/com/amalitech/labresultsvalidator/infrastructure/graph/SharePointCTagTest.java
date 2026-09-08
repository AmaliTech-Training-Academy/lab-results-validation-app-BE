package com.amalitech.labresultsvalidator.infrastructure.graph;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SharePointCTagTest {

    @Test
    void parsesTheRevisionOutOfAWellFormedCTag() {
        assertThat(SharePointCTag.parseRevision("c:{6B0CF5FB-13F3-4368-AF03-84091F227C3E},89")).isEqualTo(89);
    }

    @Test
    void toleratesTheHttpEtagStyleQuotingGraphSometimesReturns() {
        assertThat(SharePointCTag.parseRevision("\"c:{6B0CF5FB-13F3-4368-AF03-84091F227C3E},2\"")).isEqualTo(2);
    }

    @Test
    void toleratesSurroundingWhitespace() {
        assertThat(SharePointCTag.parseRevision("  c:{6B0CF5FB-13F3-4368-AF03-84091F227C3E},7  ")).isEqualTo(7);
    }

    @Test
    void returnsNullForNull() {
        assertThat(SharePointCTag.parseRevision(null)).isNull();
    }

    @Test
    void returnsNullRatherThanThrowingForAnUnrecognizedShape() {
        assertThat(SharePointCTag.parseRevision("not-a-ctag")).isNull();
        assertThat(SharePointCTag.parseRevision("")).isNull();
        assertThat(SharePointCTag.parseRevision("e:{6B0CF5FB-13F3-4368-AF03-84091F227C3E},89")).isNull();
        assertThat(SharePointCTag.parseRevision("c:{6B0CF5FB-13F3-4368-AF03-84091F227C3E}")).isNull();
    }
}
