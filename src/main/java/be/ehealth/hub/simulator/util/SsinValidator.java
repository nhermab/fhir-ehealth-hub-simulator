package be.ehealth.hub.simulator.util;

import java.util.regex.Pattern;

/**
 * Validator for Belgian National Register Social Security Identification Number (SSIN / INSS).
 */
public class SsinValidator {

    private static final Pattern DIGITS_ONLY = Pattern.compile("^\\d{11}$");

    public static class ValidationResult {
        private final boolean valid;
        private final String normalizedSsin;
        private final String errorMessage;

        public ValidationResult(boolean valid, String normalizedSsin, String errorMessage) {
            this.valid = valid;
            this.normalizedSsin = normalizedSsin;
            this.errorMessage = errorMessage;
        }

        public boolean isValid() {
            return valid;
        }

        public String getNormalizedSsin() {
            return normalizedSsin;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }

    /**
     * Normalizes an SSIN by removing dots, hyphens, and whitespace.
     */
    public static String normalize(String ssin) {
        if (ssin == null) {
            return null;
        }
        return ssin.replaceAll("[.\\-\\s]", "").trim();
    }

    /**
     * Validates an SSIN according to Belgian format requirements.
     *
     * @param ssin The SSIN string (raw or formatted)
     * @param strictChecksum Whether to strictly verify modulo-97 checksum
     * @return ValidationResult
     */
    public static ValidationResult validate(String ssin, boolean strictChecksum) {
        if (ssin == null || ssin.isBlank()) {
            return new ValidationResult(false, null, "SSIN / patient identifier is empty or null.");
        }

        String normalized = normalize(ssin);

        if (!DIGITS_ONLY.matcher(normalized).matches()) {
            return new ValidationResult(false, normalized,
                    "Invalid SSIN format: must consist of 11 numeric digits, received '" + ssin + "'.");
        }

        if (strictChecksum) {
            long base = Long.parseLong(normalized.substring(0, 9));
            int expectedChecksum = Integer.parseInt(normalized.substring(9, 11));

            // Pre-2000 check
            long remainderPre2000 = base % 97;
            int checkPre2000 = (int) (97 - remainderPre2000);

            // Post-2000 check (with leading 2)
            long remainderPost2000 = (2_000_000_000L + base) % 97;
            int checkPost2000 = (int) (97 - remainderPost2000);

            if (checkPre2000 != expectedChecksum && checkPost2000 != expectedChecksum) {
                return new ValidationResult(false, normalized,
                        "Invalid SSIN checksum: modulo-97 verification failed for '" + ssin + "'.");
            }
        }

        return new ValidationResult(true, normalized, null);
    }
}
