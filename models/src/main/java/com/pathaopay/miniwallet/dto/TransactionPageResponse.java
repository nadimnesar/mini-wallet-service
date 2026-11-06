package com.pathaopay.miniwallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

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
