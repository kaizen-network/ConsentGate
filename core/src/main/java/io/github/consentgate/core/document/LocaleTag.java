package io.github.consentgate.core.document;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

public final class LocaleTag {
    private static final Pattern VALID = Pattern.compile("[A-Za-z]{2,8}(?:[-_][A-Za-z0-9]{1,8})*");

    private LocaleTag() { }

    public static String normalize(String value) {
        if (value == null || value.length() > 64 || !VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid locale (maximum 64 characters): " + value);
        }
        String[] parts = value.replace('_', '-').split("-");
        parts[0] = parts[0].toLowerCase(Locale.ROOT);
        for (int index = 1; index < parts.length; index++) {
            String part = parts[index];
            if (part.length() == 2 && part.chars().allMatch(Character::isLetter)) {
                parts[index] = part.toUpperCase(Locale.ROOT);
            } else if (part.length() == 4 && part.chars().allMatch(Character::isLetter)) {
                parts[index] = Character.toUpperCase(part.charAt(0)) + part.substring(1).toLowerCase(Locale.ROOT);
            } else {
                parts[index] = part.toLowerCase(Locale.ROOT);
            }
        }
        return String.join("-", Arrays.asList(parts));
    }
}
