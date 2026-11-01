package com.omkar.ledger.common;

/** Small string helpers. */
public final class Names {

    private Names() {
    }

    /** {@code fromAccountId -> from_account_id}, so validation errors name the JSON field the client sent. */
    public static String toSnakeCase(String camel) {
        if (camel == null) {
            return null;
        }
        var out = new StringBuilder(camel.length() + 8);
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) {
                out.append('_').append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
