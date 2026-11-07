package com.pathaopay.miniwallet.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** One page of a wallet's ledger entries; {@code page} starts at 1. */
public record TransactionPageResponse(
        @JsonProperty("items")
        List<TransactionItemResponse> items,

        @JsonProperty("page")
        int page,

        @JsonProperty("page_size")
        int pageSize,

        @JsonProperty("total_items")
        long totalItems,

        @JsonProperty("total_pages")
        int totalPages
) {}
