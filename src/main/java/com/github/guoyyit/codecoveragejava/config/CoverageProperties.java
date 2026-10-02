package com.github.guoyyit.codecoveragejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "coverage")
public class CoverageProperties {

    private String dataDir = "data/coverage";
    private double regressionThresholdPercent = 1.0;

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
}
