package in.boss.production.domain;

import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

@Component
public class ProductionSupport {

    public BossContext requireContext() {
        BossContext context = BossContextHolder.get();
        if (context == null || context.tenantId() == null || context.userId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant user context is required");
        }
        return context;
    }

    public boolean commercial(BossContext context) {
        return context.hasAnyRole(
                "OWNER", "OPS_MANAGER", "SALES_MANAGER", "PURCHASE_MANAGER", "ACCOUNTS_STAFF");
    }

    public static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    public static BigDecimal quantity(BigDecimal value) {
        if (value == null || value.compareTo(new BigDecimal("0.001")) < 0) {
            throw badRequest("Quantity must be at least 0.001");
        }
        return value.setScale(3, java.math.RoundingMode.HALF_UP);
    }
}
