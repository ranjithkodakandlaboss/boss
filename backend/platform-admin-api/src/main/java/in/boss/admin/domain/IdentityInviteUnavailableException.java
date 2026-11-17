package in.boss.admin.domain;

/**
 * Thrown when the initial tenant owner invitation cannot be completed.
 * The message deliberately contains no email address and no token.
 */
public class IdentityInviteUnavailableException extends RuntimeException {
    public IdentityInviteUnavailableException() {
        super("Initial administrator invitation is not available");
    }

    public IdentityInviteUnavailableException(String message) {
        super(message);
    }
}
