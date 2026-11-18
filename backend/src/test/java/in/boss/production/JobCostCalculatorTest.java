package in.boss.production;

import in.boss.production.domain.JobCostCalculator;
import in.boss.production.domain.JobStatusMachine;
import in.boss.production.domain.StageStatusMachine;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobCostCalculatorTest {

    @Test
    void marginUsesSellingPriceAndDoesNotInventARate() {
        assertThat(JobCostCalculator.marginPct(new BigDecimal("1000"), new BigDecimal("50")))
                .isEqualByComparingTo("95.0000");
        assertThat(JobCostCalculator.marginPct(new BigDecimal("1000"), BigDecimal.ZERO))
                .isEqualByComparingTo("100.0000");
        assertThat(JobCostCalculator.profit(new BigDecimal("1000"), new BigDecimal("50")))
                .isEqualByComparingTo("950.00");
        assertThat(JobCostCalculator.marginPct(BigDecimal.ZERO, new BigDecimal("50"))).isNull();
        assertThat(JobCostCalculator.variance(new BigDecimal("50"), new BigDecimal("40")))
                .isEqualByComparingTo("10.00");
    }

    @Test
    void jobAndStageTransitionsFollowTheStoredStateMachine() {
        JobStatusMachine.assertTransition("planned", "released");
        JobStatusMachine.assertTransition("released", "in_progress");
        JobStatusMachine.assertTransition("in_progress", "completed");
        assertThatThrownBy(() -> JobStatusMachine.assertTransition("in_progress", "cancelled"))
                .isInstanceOf(ResponseStatusException.class);
        StageStatusMachine.assertTransition("pending", "skipped");
        assertThat(StageStatusMachine.isClosed("completed")).isTrue();
        assertThatThrownBy(() -> StageStatusMachine.assertTransition("completed", "in_progress"))
                .isInstanceOf(ResponseStatusException.class);
    }
}
