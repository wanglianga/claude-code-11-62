package com.customs.ledger.replay;

import com.customs.ledger.domain.LedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Optionally runs the replayable scenario at startup so a fresh environment has a worked example.
 * Off by default; enable with {@code ledger.seed-demo=true} or the {@code SEED_DEMO=true} env var.
 * Skips when the demo ledger already exists, so restarting against a persisted volume is safe.
 */
@Component
@ConditionalOnProperty(name = "ledger.seed-demo", havingValue = "true")
public class DemoSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeedRunner.class);
    private static final String DEMO_LEDGER = "DEMO-SEED";

    private final SampleScenarioService scenarios;
    private final LedgerRepository ledgers;

    public DemoSeedRunner(SampleScenarioService scenarios, LedgerRepository ledgers) {
        this.scenarios = scenarios;
        this.ledgers = ledgers;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (ledgers.findLedger(DEMO_LEDGER).isPresent()) {
            log.info("demo ledger {} already present; skipping seed", DEMO_LEDGER);
            return;
        }
        String period = args.containsOption("sample-period")
                ? args.getOptionValues("sample-period").getFirst() : "2026-03";
        var result = scenarios.run(DEMO_LEDGER, period);
        log.info("seeded demo ledger {} for {}, allPassed={}", result.ledgerCode(), result.period(),
                result.allPassed());
    }
}

