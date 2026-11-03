package com.assessment.txn.validation;

import com.assessment.txn.domain.TransactionRequest;
import com.assessment.txn.domain.TransactionType;
import com.assessment.txn.ingestion.RawRecord;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Pure input validation. Error messages deliberately never contain the offending value. */
public final class RequestValidator {

    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final int MAX_AMOUNT_PRECISION = 18;
    private static final int MAX_AMOUNT_SCALE = 4;

    public ValidationOutcome validate(RawRecord raw) {
        if (raw.sourceError() != null) {
            return new ValidationOutcome(null, List.of(raw.sourceError()), null);
        }
        Map<String, String> f = raw.fields();
        List<String> errors = new ArrayList<>();

        String transactionId = id(f, "transactionId", errors);
        String requestId = id(f, "requestId", errors);
        String accountId = id(f, "accountId", errors);
        Long sequence = sequence(f.get("sequenceNumber"), errors);
        TransactionType type = type(f.get("transactionType"), errors);
        BigDecimal amount = amount(f.get("amount"), errors);
        String currency = currency(f.get("currency"), errors);

        if (!errors.isEmpty()) {
            return new ValidationOutcome(null, List.copyOf(errors), sequence);
        }
        TransactionRequest request = new TransactionRequest(
                transactionId, requestId, sequence, accountId, type, amount, currency);
        return new ValidationOutcome(request, List.of(), sequence);
    }

    private static String id(Map<String, String> f, String field, List<String> errors) {
        String v = f.get(field);
        if (v == null || v.isBlank()) {
            errors.add(field + ": required");
            return null;
        }
        if (!ID.matcher(v).matches()) {
            errors.add(field + ": must be 1-64 characters of letters, digits, '.', '_' or '-'");
            return null;
        }
        return v;
    }

    private static Long sequence(String v, List<String> errors) {
        if (v == null || v.isBlank()) {
            errors.add("sequenceNumber: required");
            return null;
        }
        try {
            long n = Long.parseLong(v);
            if (n < 1) {
                errors.add("sequenceNumber: must be a positive integer");
                return null;
            }
            return n;
        } catch (NumberFormatException e) {
            errors.add("sequenceNumber: must be a positive integer");
            return null;
        }
    }

    private static TransactionType type(String v, List<String> errors) {
        if (v == null || v.isBlank()) {
            errors.add("transactionType: required");
            return null;
        }
        try {
            return TransactionType.valueOf(v.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add("transactionType: unsupported (expected CREDIT or DEBIT)");
            return null;
        }
    }

    private static BigDecimal amount(String v, List<String> errors) {
        if (v == null || v.isBlank()) {
            errors.add("amount: required");
            return null;
        }
        try {
            BigDecimal a = new BigDecimal(v);
            if (a.signum() <= 0) {
                errors.add("amount: must be greater than zero");
                return null;
            }
            BigDecimal normalized = a.stripTrailingZeros();
            int integerDigits = normalized.precision() - normalized.scale(); // huge for values like 1E999999999
            if (normalized.scale() > MAX_AMOUNT_SCALE
                    || normalized.precision() > MAX_AMOUNT_PRECISION
                    || integerDigits > MAX_AMOUNT_PRECISION - MAX_AMOUNT_SCALE) {
                errors.add("amount: too many digits (max " + MAX_AMOUNT_SCALE + " decimals, "
                        + MAX_AMOUNT_PRECISION + " significant digits)");
                return null;
            }
            return a;
        } catch (NumberFormatException e) {
            errors.add("amount: must be a decimal number");
            return null;
        }
    }

    private static String currency(String v, List<String> errors) {
        if (v == null || v.isBlank()) {
            errors.add("currency: required");
            return null;
        }
        if (!CURRENCY.matcher(v).matches()) {
            errors.add("currency: must be a 3-letter ISO 4217 code");
            return null;
        }
        try {
            Currency.getInstance(v);
            return v;
        } catch (IllegalArgumentException e) {
            errors.add("currency: unknown ISO 4217 code");
            return null;
        }
    }
}
