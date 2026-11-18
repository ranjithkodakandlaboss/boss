package in.boss.masterdata.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.sql.SQLException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ItemDependencyExceptionResolver implements HandlerExceptionResolver {
    private static final String ITEM_UNAVAILABLE = "23B01";
    private final ObjectMapper objectMapper;

    public ItemDependencyExceptionResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
                                         Object handler, Exception exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof SQLException sqlException && ITEM_UNAVAILABLE.equals(sqlException.getSQLState())) {
                ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                        "The selected item is inactive or unavailable. Reactivate it before using it in an active record.");
                detail.setType(URI.create("urn:boss:error:item-unavailable"));
                detail.setTitle("Item unavailable");
                response.setStatus(HttpStatus.CONFLICT.value());
                response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                try {
                    objectMapper.writeValue(response.getOutputStream(), detail);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return new ModelAndView();
            }
            cause = cause.getCause();
        }
        return null;
    }
}
