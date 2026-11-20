package com.customs.ledger.replay;

import com.customs.ledger.calc.Computation;
import com.customs.ledger.calc.LedgerCalculator;
import com.customs.ledger.calc.Snapshot;
import com.customs.ledger.books.Close;
import com.customs.ledger.books.CloseQueryService;
import com.customs.ledger.books.CloseRepository;
import com.customs.ledger.books.CloseService;
import com.customs.ledger.books.CloseView;
import com.customs.ledger.common.ApiException;
import com.customs.ledger.domain.Ledger;
import com.customs.ledger.domain.LedgerRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reproduces a reported close from its frozen snapshot and reconciles the result with the stored one. */
@Service
public class ReproduceService {

    private final LedgerRepository ledgers;
    private final CloseRepository closes;
    private final CloseQueryService queries;
    private final LedgerCalculator calculator;

    public ReproduceService(LedgerRepository ledgers, CloseRepository closes, CloseQueryService queries,
                            LedgerCalculator calculator) {
        this.ledgers = ledgers;
        this.closes = closes;
        this.queries = queries;
        this.calculator = calculator;
    }

    public record Reproduction(Close close, String storedResultHash, String recomputedResultHash,
                               boolean hashMatch, boolean materialized,
                               List<BalanceComparison> balances, List<String> warnings) {
    }

    public record BalanceComparison(String material, BigDecimal reportedClosing, BigDecimal reproducedClosing,
                                    BigDecimal reportedConsumed, BigDecimal reproducedConsumed,
                                    BigDecimal reportedOutbound, BigDecimal reproducedOutbound, boolean equal) {
    }

    public Reproduction reproduce(String ledgerCode, long closeId) {
        Ledger ledger = ledgers.requireLedger(ledgerCode);
        Close close = closes.requireById(closeId);
        if (!close.ledgerCode().equals(ledger.code())) {
            throw ApiException.notFound("CLOSE_NOT_FOUND", "close " + closeId + " does not belong to " + ledgerCode);
        }
        Snapshot snapshot = closes.snapshot(close.id());
        Computation computation = calculator.compute(snapshot);
        String recomputedHash = CloseService.resultHash(computation);

        Map<String, CloseView.MaterialBalance> stored = new LinkedHashMap<>();
        for (CloseView.MaterialBalance b : closes.materialBalances(close.id())) {
            stored.put(b.material(), b);
        }
        List<BalanceComparison> comparisons = new ArrayList<>();
        for (Computation.MaterialResult r : computation.materials()) {
            CloseView.MaterialBalance s = stored.get(r.materialCode());
            BigDecimal reportedClosing = s == null ? null : s.closing();
            BigDecimal reportedConsumed = s == null ? null : s.consumed();
            BigDecimal reportedOutbound = s == null ? null : s.outbound();
            boolean equal = s != null
                    && reportedClosing != null && reportedClosing.compareTo(r.closing()) == 0
                    && reportedConsumed.compareTo(r.consumed()) == 0
                    && reportedOutbound.compareTo(r.outbound()) == 0;
            comparisons.add(new BalanceComparison(r.materialCode(), reportedClosing, r.closing(),
                    reportedConsumed, r.consumed(), reportedOutbound, r.outbound(), equal));
        }
        List<String> warnings = new ArrayList<>();
        if (!close.algorithmVersion().equals(LedgerCalculator.ALGORITHM)) {
            warnings.add("stored close used algorithm " + close.algorithmVersion()
                    + " but this server runs " + LedgerCalculator.ALGORITHM
                    + "; reproduction is only guaranteed for the matching algorithm version");
        }
        boolean allEqual = comparisons.stream().allMatch(BalanceComparison::equal);
        if (!recomputedHash.equals(close.resultHash())) {
            warnings.add("recomputed result hash differs from the stored hash");
        }
        return new Reproduction(close, close.resultHash(), recomputedHash,
                recomputedHash.equals(close.resultHash()) && allEqual,
                close.mode().equals("MATERIALIZED"), comparisons, warnings);
    }
}
