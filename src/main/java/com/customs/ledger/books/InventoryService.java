package com.customs.ledger.books;

import com.customs.ledger.common.ApiException;
import com.customs.ledger.domain.Ledger;
import com.customs.ledger.domain.LedgerRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/** Batch-level stock and work-in-process report for a close. */
@Service
public class InventoryService {

    private final LedgerRepository ledgers;
    private final CloseRepository closes;

    public InventoryService(LedgerRepository ledgers, CloseRepository closes) {
        this.ledgers = ledgers;
        this.closes = closes;
    }

    public record Report(long closeId, String period, int version, List<CloseRepository.InventoryRow> stock,
                         List<CloseRepository.InventoryRow> wip) {
    }

    public Report report(String ledgerCode, long closeId) {
        Ledger ledger = ledgers.requireLedger(ledgerCode);
        Close close = closes.requireById(closeId);
        if (!close.ledgerCode().equals(ledger.code())) {
            throw ApiException.notFound("CLOSE_NOT_FOUND", "close " + closeId + " does not belong to " + ledgerCode);
        }
        List<CloseRepository.InventoryRow> rows = closes.inventory(close.id());
        List<CloseRepository.InventoryRow> stock = rows.stream().filter(r -> r.pool().equals("STOCK")).toList();
        List<CloseRepository.InventoryRow> wip = rows.stream().filter(r -> r.pool().equals("WIP")).toList();
        return new Report(close.id(), close.period(), close.version(), stock, wip);
    }
}
