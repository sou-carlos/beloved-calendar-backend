package br.com.beloved.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
    public record ApiError(String code, String message, Map<String, String> fieldErrors) {}

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException error) {
        Map<String, String> fields = new LinkedHashMap<>();
        error.getBindingResult().getFieldErrors().forEach(field ->
            fields.putIfAbsent(field.getField(), field.getDefaultMessage()));
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_ERROR", "Confira os campos informados.", fields));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiError> authentication() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(new ApiError("INVALID_CREDENTIALS", "Email ou senha incorretos.", Map.of()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> duplicateEmail() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ApiError("EMAIL_IN_USE", "Já existe uma conta com este email.", Map.of()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> status(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode())
            .body(new ApiError("REQUEST_ERROR", error.getReason(), Map.of()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> malformedBody() {
        return ResponseEntity.badRequest()
            .body(new ApiError("INVALID_JSON", "Não foi possível ler os dados enviados.", Map.of()));
    }
}
