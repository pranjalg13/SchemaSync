package com.schemasync.branch;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "schemasync")
public record SchemaSyncProperties(
        String mainSchema,
        String branchSchemaPrefix,
        int branchSampleRows,
        Migration migration) {

    public record Migration(
            Duration lockTimeout,
            Duration ddlStatementTimeout,
            Duration validateStatementTimeout,
            Duration batchStatementTimeout,
            int maxLockRetries,
            Duration maxBackoff,
            Duration targetBatchDuration,
            int initialBatchSize,
            int minBatchSize,
            int maxBatchSize,
            double throttleRatio,
            long onlineModeRowThreshold,
            Duration heartbeatInterval,
            Duration staleClaimAfter) {}
}
