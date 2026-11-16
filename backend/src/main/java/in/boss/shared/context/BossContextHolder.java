package in.boss.shared.context;

public final class BossContextHolder {

    private static final ThreadLocal<BossContext> CONTEXT = new ThreadLocal<>();

    private BossContextHolder() {}

    public static void set(BossContext ctx) {
        CONTEXT.set(ctx);
    }

    public static BossContext get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }

    public static boolean isSet() {
        return CONTEXT.get() != null;
    }
}
