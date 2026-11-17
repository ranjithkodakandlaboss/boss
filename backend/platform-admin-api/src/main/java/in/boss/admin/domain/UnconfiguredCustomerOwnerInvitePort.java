package in.boss.admin.domain;

import java.util.UUID;

public class UnconfiguredCustomerOwnerInvitePort implements CustomerOwnerInvitePort {
    @Override
    public OwnerInvite inviteInitialOwner(UUID tenantId, String email) {
        throw new IdentityInviteUnavailableException();
    }
}
