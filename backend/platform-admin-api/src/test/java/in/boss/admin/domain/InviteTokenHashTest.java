package in.boss.admin.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InviteTokenHashTest {
    @Test
    void ownerInviteUsesTheCustomerActivationHash() {
        assertThat(JdbcCustomerOwnerInvitePort.sha256("boss-invite-vector"))
                .isEqualTo("2afefd25a6ce60f1162a30034b90174e90742854b7885c2d8bda423657820f00");
    }
}
