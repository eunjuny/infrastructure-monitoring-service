package dev.infrastructure.monitoring.resource;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ResourceExceptionHandler {
    @ExceptionHandler(ResourceRequestException.class)
    public ProblemDetail request(ResourceRequestException exception) {
        HttpStatus status = exception.reason() == ResourceRequestException.Reason.RESOURCE_NOT_FOUND
                ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        var problem = problem(status, exception.reason().name(), exception.getMessage());
        if (exception.field() != null) problem.setProperty("field", exception.field());
        return problem;
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ProblemDetail binding(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "필수 값 또는 요청값의 형식을 확인해 주세요.");
    }

    @ExceptionHandler(QueryTimeoutException.class)
    public ProblemDetail timeout(QueryTimeoutException exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "QUERY_TIMEOUT", "조회 시간이 초과되었습니다. 조건을 좁혀 다시 시도해 주세요.");
    }

    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class})
    public ProblemDetail storage(Exception exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "저장소 조회를 완료하지 못했습니다.");
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail) {
        var result = ProblemDetail.forStatusAndDetail(status, detail);
        result.setTitle(status.getReasonPhrase());
        result.setProperty("code", code);
        return result;
    }
}
