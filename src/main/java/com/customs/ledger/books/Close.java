package com.customs.ledger.books;


import java.time.OffsetDateTime;

/** Persisted monthly close. Immutable once written; a new version supersedes it. */
public record Close(
        long id, String ledgerCode, String period, int version, String status, String mode,
        String algorithmVersion, Long priorCloseId, Long adjustmentOfCloseId, String inputHash,
        String resultHash, int documentCount, OffsetDateTime closedAt, String closedBy, String note) {
}
