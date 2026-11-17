package in.boss.identity;

import static org.assertj.core.api.Assertions.assertThat;

import in.boss.identity.domain.CustomerIdentityService;
import org.junit.jupiter.api.Test;

class InviteTokenHashTest {
    static final String TOKEN = "boss-invite-vector";
    static final String HASH = "2afefd25a6ce60f1162a30034b90174e90742854b7885c2d8bda423657820f00";

    @Test
    void customerActivationUsesThePlatformAdminTokenHash() {
        assertThat(CustomerIdentityService.sha256(TOKEN)).isEqualTo(HASH);
    }
}
