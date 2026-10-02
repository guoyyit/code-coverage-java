package com.github.guoyyit.codecoveragejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "coverage")
public class CoverageProperties {

    private String dataDir = "coverage-data";
    private double regressionThresholdPercent = 1.0d;
    private boolean failOnNewUncoveredCode = true;

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public double getRegressionThresholdPercent() {
        return regressionThresholdPercent;
    }

    public void setRegressionThresholdPercent(double regressionThresholdPercent) {
        this.regressionThresholdPercent = regressionThresholdPercent;
    }

    public boolean isFailOnNewUncoveredCode() {
        return failOnNewUncoveredCode;
    }

    public void setFailOnNewUncoveredCode(boolean failOnNewUncoveredCode) {
        this.failOnNewUncoveredCode = failOnNewUncoveredCode;
    }
}
