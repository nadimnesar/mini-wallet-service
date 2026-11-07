package com.pathaopay.miniwallet.common.util;

import com.pathaopay.miniwallet.common.constant.AppConstants;
import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorMessage;
import com.pathaopay.miniwallet.common.exception.wallet.InvalidPhoneNumberException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes Bangladeshi mobile numbers to the canonical 11-digit local form ({@code 01XXXXXXXXX}).
 *
 * <p>Accepted inputs: {@code 1XXXXXXXXX}, {@code 01XXXXXXXXX}, {@code 8801XXXXXXXXX} and
 * {@code +8801XXXXXXXXX}.
 * Surrounding whitespace is ignored.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PhoneNumberUtils {

    private static final Pattern ACCEPTED = Pattern.compile(AppConstants.PHONE_ACCEPTED_PATTERN);
    private static final Pattern CUSTOMER = Pattern.compile(AppConstants.CUSTOMER_PHONE_ACCEPTED_PATTERN);

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
        return AppConstants.PHONE_LOCAL_PREFIX + matcher.group(1);
    }

    /**
     * Like {@link #normalize}, but also requires an assigned operator prefix (013-019): the rule for registering a
     * customer wallet. Lookups use {@link #normalize}, which also accepts the reserved system-account numbers.
     *
     * @throws InvalidPhoneNumberException if {@code phone} is null, malformed or has an unassigned prefix
     */
    public static String normalizeCustomer(String phone) {
        String normalized = normalize(phone);
        if (!CUSTOMER.matcher(normalized).matches()) {
            throw new InvalidPhoneNumberException(ErrorMessage.CUSTOMER_PHONE_FORMAT);
        }
        return normalized;
    }
}
