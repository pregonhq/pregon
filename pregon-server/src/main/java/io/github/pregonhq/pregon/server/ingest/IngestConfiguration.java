package io.github.pregonhq.pregon.server.ingest;

import java.time.Clock;

import io.github.pregonhq.pregon.core.ingest.SegmentQueue;
import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires ingestion (ADR-0024) and the queue (ADR-0025): tunables, the one clock everything reads,
 * the request filters in cost order, and the daily partition maintenance.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class IngestConfiguration {

    /** Spring Security's filter chain order (SECURITY_FILTER_ORDER): the filters sit on either side of it. */
    static final int SECURITY_FILTER_ORDER = -100;

    @Bean
    IngestProperties ingestProperties(Environment environment) {
        return IngestProperties.from(environment);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    SegmentQueue segmentQueue(JdbcClient jdbc, TransactionTemplate transaction, Clock clock, IngestProperties properties) {
        return new PostgresSegmentQueue(jdbc, transaction, clock, properties.maxAttempts());
    }

    @Bean
    SegmentIngestion segmentIngestion(JdbcClient jdbc, TransactionTemplate transaction, Clock clock, IngestProperties properties) {
        return new SegmentIngestion(jdbc, transaction, clock, properties);
    }

    @Bean
    SegmentPartitions segmentPartitions(JdbcClient jdbc, Clock clock, IngestProperties properties) {
        SegmentPartitions partitions = new SegmentPartitions(jdbc, clock, properties.retentionSegments());
        partitions.maintain();
        return partitions;
    }

    @Bean
    PartitionMaintenance partitionMaintenance(SegmentPartitions partitions) {
        return new PartitionMaintenance(partitions);
    }

    /** Once a day: the next months exist before midnight on the first, and months past retention are dropped. */
    static final class PartitionMaintenance {

        private final SegmentPartitions partitions;

        PartitionMaintenance(SegmentPartitions partitions) {
            this.partitions = partitions;
        }

        @Scheduled(fixedRateString = "P1D", initialDelayString = "P1D")
        void daily() {
            partitions.maintain();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication
    static class Web {

        @Bean
        InflightBudget inflightBudget(IngestProperties properties) {
            return new InflightBudget(properties.inflightMaxBytes());
        }

        /**
         * There is exactly one size limit and it is ours (ADR-0025 §4): the container's limits sit
         * just above it so they never answer first, and the threshold keeps every part in memory,
         * because audio must not touch disk (ADR-0006).
         */
        @Bean
        MultipartConfigElement multipartConfigElement(IngestProperties properties) {
            long maxRequest = properties.maxBodyBytes() + IngestProperties.MULTIPART_OVERHEAD;
            int inMemory = (int) Math.min(Integer.MAX_VALUE, maxRequest + 1);
            return new MultipartConfigElement(System.getProperty("java.io.tmpdir"), maxRequest, maxRequest, inMemory);
        }

        /** Before authentication: rejections that need only the request line and headers. */
        @Bean
        FilterRegistrationBean<IngestPrechecksFilter> ingestPrechecks(IngestProperties properties) {
            FilterRegistrationBean<IngestPrechecksFilter> registration =
                    new FilterRegistrationBean<>(new IngestPrechecksFilter(properties.maxBodyBytes()));
            registration.addUrlPatterns("/v1/segments/*");
            registration.setOrder(SECURITY_FILTER_ORDER - 2);
            return registration;
        }

        /** Right after the prechecks and before authentication: an upload holds its budget before any database lookup. */
        @Bean
        FilterRegistrationBean<InflightBudgetFilter> inflightBudgetFilter(InflightBudget budget, IngestProperties properties) {
            FilterRegistrationBean<InflightBudgetFilter> registration = new FilterRegistrationBean<>(
                    new InflightBudgetFilter(budget, properties.maxBodyBytes(), Math.max(1, properties.retryAfterMin().toSeconds())));
            registration.addUrlPatterns("/v1/segments/*");
            registration.setOrder(SECURITY_FILTER_ORDER - 1);
            return registration;
        }
    }
}
