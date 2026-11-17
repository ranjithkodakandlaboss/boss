package in.boss.inventory.api;

import in.boss.inventory.domain.InvalidLotTransitionException;
import in.boss.inventory.domain.InventoryValidationException;
import in.boss.inventory.domain.LotNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice(basePackageClasses = InventoryController.class)
public class InventoryExceptionHandler {

    @ExceptionHandler(LotNotFoundException.class)
    public ProblemDetail handleNotFound(LotNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create("urn:boss:error:not-found"));
        pd.setTitle("Lot Not Found");
        return pd;
    }

    @ExceptionHandler(InvalidLotTransitionException.class)
    public ProblemDetail handleTransition(InvalidLotTransitionException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(URI.create("urn:boss:error:invalid-lot-transition"));
        pd.setTitle("Invalid Lot Status Transition");
        return pd;
    }

    @ExceptionHandler(InventoryValidationException.class)
    public ProblemDetail handleValidation(InventoryValidationException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setType(URI.create("urn:boss:error:bad-request"));
        pd.setTitle("Invalid Inventory Request");
        return pd;
    }
}
