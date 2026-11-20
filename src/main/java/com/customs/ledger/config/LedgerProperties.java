package com.customs.ledger.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ledger")
public class LedgerProperties {

    /** Default time zone used to derive business periods. */
    private String zone = "Asia/Shanghai";

    /** Version tag stored on every close so old results can be reproduced. */
    private String algorithmVersion = "ledger-1.0.0";

    private final Materialization materialization = new Materialization();

    public String getZone() {
        return zone;
    }

    public void setZone(String zone) {
        this.zone = zone;
    }

    public String getAlgorithmVersion() {
        return algorithmVersion;
    }

    public void setAlgorithmVersion(String algorithmVersion) {
        this.algorithmVersion = algorithmVersion;
    }

    public Materialization getMaterialization() {
        return materialization;
    }

    public static class Materialization {

        /** MATERIALIZED or REBUILD. */
        private String defaultMode = "MATERIALIZED";

        /** When default-mode is AUTO, closes above this document count use REBUILD. */
        private int autoThresholdDocuments = 500;

        public String getDefaultMode() {
            return defaultMode;
        }

        public void setDefaultMode(String defaultMode) {
            this.defaultMode = defaultMode;
        }

        public int getAutoThresholdDocuments() {
            return autoThresholdDocuments;
        }

        public void setAutoThresholdDocuments(int autoThresholdDocuments) {
            this.autoThresholdDocuments = autoThresholdDocuments;
        }
    }
}
