package dev.asambataiden.payment.payments.api.dto;

import dev.asambataiden.payment.payments.application.PaymentSearchService.SearchPage;
import java.util.List;

/** Bounded keyset page; no tenant identifier may be supplied by the caller. */
public record PaymentSearchResponse(List<PaymentResponse> payments, String nextCursor, boolean hasMore) {
    public static PaymentSearchResponse from(SearchPage page) {
        return new PaymentSearchResponse(
                page.payments().stream().map(PaymentResponse::from).toList(),
                page.nextCursor(), page.hasMore());
    }
}
