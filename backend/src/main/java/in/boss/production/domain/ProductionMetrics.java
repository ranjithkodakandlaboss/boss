package in.boss.production.domain;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Production meters. Labels stay on bounded outcomes. Tenant and customer identifiers are not labels.
 * Counters are recorded after the transaction commits so a rollback is not counted as a success.
 */
@Component
public class ProductionMetrics {

    private final MeterRegistry registry;

    public ProductionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void jobCreation(String result) {
        counter("boss.production.job.creation", "result", result);
    }

    public void jobTransition(String from, String to) {
        Counter.builder("boss.production.job.transition")
                .tag("from", from)
                .tag("to", to)
                .register(registry)
                .increment();
    }

    public void stageOutcome(String result) {
        counter("boss.production.stage.completion", "result", result);
    }

    public void stageDuration(Duration duration) {
        Timer.builder("boss.production.stage.duration")
                .publishPercentileHistogram(false)
                .register(registry)
                .record(duration);
    }

    public void material(String type, String result) {
        Counter.builder("boss.production.material.movement")
                .tag("type", type)
                .tag("result", result)
                .register(registry)
                .increment();
    }

    public void inventory(String operation, String result) {
        Counter.builder("boss.production.inventory.integration")
                .tag("operation", operation)
                .tag("result", result)
                .register(registry)
                .increment();
    }

    public void costing(String result) {
        counter("boss.production.costing.calculation", "result", result);
    }

    public void variance(BigDecimal amount) {
        DistributionSummary.builder("boss.production.cost.variance")
                .register(registry)
                .record(amount == null ? 0d : amount.doubleValue());
    }

    public void inspectionGate(String result) {
        counter("boss.production.inspection.gate", "result", result);
    }

    public void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private void counter(String name, String tag, String value) {
        Counter.builder(name).tag(tag, value).register(registry).increment();
    }
}
