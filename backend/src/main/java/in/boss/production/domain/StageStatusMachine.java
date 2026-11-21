package in.boss.production.domain;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Job stage transitions stored by V025.
 */
public final class StageStatusMachine {

    public static final String PENDING = "pending";
    public static final String IN_PROGRESS = "in_progress";
    public static final String COMPLETED = "completed";
    public static final String SKIPPED = "skipped";

    private StageStatusMachine() {}

    public static void assertTransition(String from, String to) {
        if (!allowed(from, to)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot move stage from " + from + " to " + to);
        }
    }

    public static boolean allowed(String from, String to) {
        return switch (from) {
            case PENDING -> IN_PROGRESS.equals(to) || SKIPPED.equals(to);
            case IN_PROGRESS -> COMPLETED.equals(to);
            default -> false;
        };
    }

    public static boolean isClosed(String status) {
        return COMPLETED.equals(status) || SKIPPED.equals(status);
    }
}
