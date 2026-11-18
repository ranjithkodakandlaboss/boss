package in.boss.production.domain;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Production job transitions stored by V025.
 */
public final class JobStatusMachine {

    public static final String PLANNED = "planned";
    public static final String RELEASED = "released";
    public static final String IN_PROGRESS = "in_progress";
    public static final String COMPLETED = "completed";
    public static final String CANCELLED = "cancelled";
    public static final String ON_HOLD = "on_hold";

    private JobStatusMachine() {}

    public static void assertTransition(String from, String to) {
        if (!allowed(from, to)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot move job from " + from + " to " + to);
        }
    }

    public static boolean allowed(String from, String to) {
        return switch (from) {
            case PLANNED -> RELEASED.equals(to) || CANCELLED.equals(to);
            case RELEASED -> IN_PROGRESS.equals(to) || CANCELLED.equals(to);
            case IN_PROGRESS -> ON_HOLD.equals(to) || COMPLETED.equals(to);
            case ON_HOLD -> IN_PROGRESS.equals(to) || CANCELLED.equals(to);
            default -> false;
        };
    }
}
