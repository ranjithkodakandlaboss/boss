package in.boss.production.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

@RestControllerAdvice(basePackageClasses = ProductionJobController.class)
public class ProductionExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProductionExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail status(ResponseStatusException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(ex.getStatusCode(), ex.getReason());
        detail.setTitle("Production request was rejected");
        detail.setType(URI.create("urn:boss:error:production"));
        return detail;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail duplicate(DataIntegrityViolationException ex) {
        log.error("Data integrity violation in production: {}", ex.getMessage(), ex);
        String msg = ex.getMessage() != null ? ex.getMessage() : "";
        if (msg.contains("cpo_line") || msg.contains("uq_production_jobs_open_cpo_line")) {
            ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "A production job already exists for this Customer PO line");
            detail.setTitle("Duplicate production job");
            detail.setType(URI.create("urn:boss:error:production-conflict"));
            return detail;
        }
        String reason = ex.getMostSpecificCause() != null ? ex.getMostSpecificCause().getMessage() : ex.getMessage();
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, reason);
        detail.setTitle("Data integrity violation");
        detail.setType(URI.create("urn:boss:error:production-integrity"));
        return detail;
    }
}
