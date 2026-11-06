package com.pathaopay.miniwallet.common.util;

import com.pathaopay.miniwallet.common.constant.AppConstants.Phone;
import com.pathaopay.miniwallet.common.exception.wallet.InvalidPhoneNumberException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes Bangladeshi mobile numbers to the canonical 11-digit local form ({@code 01XXXXXXXXX}).
 *
 * <p>Accepted inputs: {@code 1XXXXXXXXX}, {@code 01XXXXXXXXX}, {@code 8801XXXXXXXXX} and
 * {@code +8801XXXXXXXXX}.
 * Surrounding whitespace is ignored.
 */
public final class PhoneNumberUtils {

    private static final Pattern ACCEPTED = Pattern.compile(Phone.ACCEPTED_PATTERN);

    private PhoneNumberUtils() {
    }

    /** Returns {@code true} if {@code phone} is in one of the accepted formats. */
    public static boolean isValid(String phone) {
        return phone != null && ACCEPTED.matcher(phone.strip()).matches();
    }

    /**
     * Converts {@code phone} to {@code 01XXXXXXXXX}.
     *
     * @throws InvalidPhoneNumberException if {@code phone} is null or not in an accepted format
     */
    public static String normalize(String phone) {
        if (phone == null) {
            throw new InvalidPhoneNumberException();
        }
        Matcher matcher = ACCEPTED.matcher(phone.strip());
        if (!matcher.matches()) {
            throw new InvalidPhoneNumberException();
        }
        return Phone.LOCAL_PREFIX + matcher.group(1);
    }
}
