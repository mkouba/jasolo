package com.github.mkouba.jasolo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GavTest {

    @Test
    void parse() {
        Gav gav = Gav.parse("io.quarkus:quarkus-core:3.27.5.1");
        assertEquals("io.quarkus", gav.groupId());
        assertEquals("quarkus-core", gav.artifactId());
        assertEquals("3.27.5.1", gav.version());
        assertEquals("io.quarkus:quarkus-core", gav.ga());
    }

    @Test
    void parseTrimsWhitespace() {
        Gav gav = Gav.parse(" io.quarkus : quarkus-core : 3.27.5.1 ");
        assertEquals("io.quarkus", gav.groupId());
        assertEquals("quarkus-core", gav.artifactId());
        assertEquals("3.27.5.1", gav.version());
    }

    @Test
    void parseInvalid() {
        assertThrows(IllegalArgumentException.class, () -> Gav.parse("io.quarkus:quarkus-core"));
        assertThrows(IllegalArgumentException.class, () -> Gav.parse("io.quarkus:quarkus-core:1.0:classifier"));
        assertThrows(IllegalArgumentException.class, () -> Gav.parse("io.quarkus::1.0"));
        assertThrows(IllegalArgumentException.class, () -> Gav.parse(null));
    }

    @Test
    void snapshot() {
        assertTrue(Gav.parse("org.example:lib:2.0.1-SNAPSHOT").isSnapshot());
        assertFalse(Gav.parse("org.example:lib:2.0.1").isSnapshot());
    }
}
