package in.boss.masterdata.api;

import in.boss.masterdata.domain.CircularReferenceException;
import in.boss.masterdata.domain.DuplicateEntityException;
import in.boss.masterdata.domain.EntityNotFoundException;
import in.boss.masterdata.domain.LifecycleException;
import in.boss.masterdata.domain.MaxBomLevelExceededException;
import in.boss.masterdata.domain.ValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.stream.Collectors;

@RestControllerAdvice
public class MasterDataExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public ProblemDetail handleNotFound(EntityNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create("urn:boss:error:not-found"));
        pd.setTitle("Resource Not Found");
        return pd;
    }

    @ExceptionHandler(DuplicateEntityException.class)
    public ProblemDetail handleDuplicate(DuplicateEntityException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(URI.create("urn:boss:error:conflict"));
        pd.setTitle("Resource Conflict");
        return pd;
    }

    @ExceptionHandler({ValidationException.class, CircularReferenceException.class, MaxBomLevelExceededException.class, LifecycleException.class})
    public ProblemDetail handleBadRequest(RuntimeException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setType(URI.create("urn:boss:error:bad-request"));
        pd.setTitle("Validation or Business Rule Error");
        return pd;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatus(ResponseStatusException ex) {
        HttpStatusCode status = ex.getStatusCode();
        String detail = ex.getReason() != null ? ex.getReason() : "The request was rejected";
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(status instanceof HttpStatus http ? http.getReasonPhrase() : "Request rejected");
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        pd.setType(URI.create("urn:boss:error:validation"));
        pd.setTitle("Invalid Request Content");
        return pd;
    }
}
