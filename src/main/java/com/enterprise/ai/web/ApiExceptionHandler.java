package com.enterprise.ai.web;

import com.enterprise.ai.advisor.GuardrailViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(GuardrailViolationException.class)
    public ProblemDetail onGuardrail(GuardrailViolationException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        pd.setTitle("Guardrail violation");
        return pd;
    }

    @ExceptionHandler(RuntimeException.class)
    public ProblemDetail onUpstreamFailure(RuntimeException e) {
        log.error("Chat request failed", e);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "The AI pipeline failed (model, vector store or tool error). See server logs.");
        pd.setTitle("Upstream failure");
        return pd;
    }
}
