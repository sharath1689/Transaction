package com.assessment.txn.observability;

/** Helpers to keep customer identifiers out of logs and reports. */
public final class Masking {
    private Masking() {}

    /** "ACC-201" becomes "***201". Very short identifiers are fully masked. */
    public static String account(String accountId) {
        if (accountId == null || accountId.length() <= 4) {
            return "***";
        }
        return "***" + accountId.substring(accountId.length() - 3);
    }
}
