package com.pathaopay.miniwallet.dto.request;

import com.pathaopay.miniwallet.model.enums.EntryType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.BindParam;

import java.time.Instant;

/**
 * Query parameters of the transaction-history endpoint (all optional): the filters and the page. The wallet is
 * the path variable.
 */
public record TransactionHistoryParams(
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

    /** Applies the defaults: page 1 and page size 20 when absent. */
    public TransactionHistoryParams {
        page = page == null ? DEFAULT_PAGE : page;
        pageSize = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
    }
}
