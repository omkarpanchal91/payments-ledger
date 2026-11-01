package com.omkar.ledger.support;

import java.util.UUID;

/** Unique ids per test so tests never collide in the shared database. */
public final class TestIds {

    private TestIds() {
    }

    public static String unique(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
