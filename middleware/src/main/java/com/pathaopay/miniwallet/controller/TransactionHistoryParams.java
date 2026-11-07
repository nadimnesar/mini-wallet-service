package com.pathaopay.miniwallet.controller;

import com.pathaopay.miniwallet.model.enums.EntryType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.BindParam;

import java.time.Instant;

/**
 * Query parameters shared by the two transaction-history endpoints (all optional): the filters and the page.
 * {@code phone} is the wallet to look at; the wallet endpoint takes it from its path instead (see {@link #forPhone}).
 */
public record TransactionHistoryParams(
        String phone,
        EntryType type,

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        Instant from,

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        Instant to,

        @Min(value = 1, message = "page must be at least 1")
        Integer page,

        @BindParam("page_size")
        @Min(value = 1, message = "page_size must be at least 1")
        @Max(value = 100, message = "page_size cannot exceed 100")
        Integer pageSize
) {
    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;

    public TransactionHistoryParams {
        page = page == null ? DEFAULT_PAGE : page;
        pageSize = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
    }

    /** The same parameters for the wallet with this phone (a path variable must not be overridden by a query value). */
    public TransactionHistoryParams forPhone(String phone) {
        return new TransactionHistoryParams(phone, type, from, to, page, pageSize);
    }
}
