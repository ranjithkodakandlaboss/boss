package in.boss.inventory.events;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class InventoryMetrics {

    private final MeterRegistry registry;

    public InventoryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void reservation(String outcome) {
        increment("boss.inventory.reservations", outcome);
    }

    public void materialIssue() {
        increment("boss.inventory.material_issues", "completed");
    }

    public void materialReturn() {
        increment("boss.inventory.material_returns", "completed");
    }

    public void replenishmentRequest(String outcome) {
        increment("boss.inventory.replenishment_requests", outcome);
    }

    private void increment(String name, String outcome) {
        Runnable record = () -> registry.counter(name, "outcome", outcome).increment();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            record.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                record.run();
            }
        });
    }
}
