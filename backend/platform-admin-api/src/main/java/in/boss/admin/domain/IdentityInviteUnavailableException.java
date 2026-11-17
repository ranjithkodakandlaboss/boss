package in.boss.admin.domain;

/**
 * The shared identity contract cannot create the initial tenant owner yet.
 * The message deliberately contains no email address and no token.
 */
public class IdentityInviteUnavailableException extends RuntimeException {
    public IdentityInviteUnavailableException() {
        super("Initial administrator invitation is not available");
    }
}
