package in.boss.dispatch.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DispatchStatusMachineTest {

    @Test
    void allowsDocumentedTransitions() {
        assertThat(DispatchStatusMachine.allows("draft", "pending_docs")).isTrue();
        assertThat(DispatchStatusMachine.allows("draft", "dispatched")).isTrue();
        assertThat(DispatchStatusMachine.allows("draft", "cancelled")).isTrue();
        assertThat(DispatchStatusMachine.allows("pending", "dispatched")).isTrue();
        assertThat(DispatchStatusMachine.allows("pending", "cancelled")).isTrue();
        assertThat(DispatchStatusMachine.allows("pending_docs", "dispatched")).isTrue();
        assertThat(DispatchStatusMachine.allows("pending_docs", "cancelled")).isTrue();
        assertThat(DispatchStatusMachine.allows("packed", "dispatched")).isTrue();
        assertThat(DispatchStatusMachine.allows("packed", "cancelled")).isTrue();
        assertThat(DispatchStatusMachine.allows("dispatched", "in_transit")).isTrue();
        assertThat(DispatchStatusMachine.allows("dispatched", "delivered")).isTrue();
        assertThat(DispatchStatusMachine.allows("dispatched", "delayed")).isTrue();
        assertThat(DispatchStatusMachine.allows("in_transit", "delivered")).isTrue();
        assertThat(DispatchStatusMachine.allows("in_transit", "delayed")).isTrue();
        assertThat(DispatchStatusMachine.allows("delayed", "in_transit")).isTrue();
        assertThat(DispatchStatusMachine.allows("delayed", "delivered")).isTrue();
    }

    @Test
    void rejectsSkippedOrTerminalTransitions() {
        assertThat(DispatchStatusMachine.allows("draft", "delivered")).isFalse();
        assertThat(DispatchStatusMachine.allows("draft", "in_transit")).isFalse();
        assertThat(DispatchStatusMachine.allows("pending", "delivered")).isFalse();
        assertThat(DispatchStatusMachine.allows("delivered", "dispatched")).isFalse();
        assertThat(DispatchStatusMachine.allows("cancelled", "draft")).isFalse();
        assertThat(DispatchStatusMachine.allows("nope", "dispatched")).isFalse();
        assertThat(DispatchStatusMachine.allows(null, "dispatched")).isFalse();
    }
}
