package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.execution.RunService.CapacityExceededException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    public ResponseEntity<ProblemDetail> badRequest(Exception e, jakarta.servlet.http.HttpServletRequest request) {
        if (request.getServletPath().contains("/ci/"))
            return problem(HttpStatus.BAD_REQUEST, "CI 参数无效，请核对工作流、作业、提交 SHA 和日志内容；粘贴日志最多 200,000 字符。");
        if (request.getServletPath().startsWith("/api/development"))
            return problem(HttpStatus.BAD_REQUEST, "仓库参数无效，请使用 owner/repository 或标准 GitHub 仓库链接，并核对所选仓库与移除确认。");
        if (request.getServletPath().startsWith("/api/source-projects")) return problem(HttpStatus.BAD_REQUEST, "源码参数无效，请检查项目名称、本机目录、服务与搜索内容。");
        if (request.getServletPath().startsWith("/api/history") || request.getServletPath().startsWith("/api/statistics"))
            return problem(HttpStatus.BAD_REQUEST, "历史筛选参数无效，请检查服务、状态、模式、日期范围、分页或删除确认。");
        if (request.getServletPath().startsWith("/api/settings"))
            return problem(HttpStatus.BAD_REQUEST, "模型配置参数无效，请检查必填项、参数类型和范围。");
        if (request.getServletPath().startsWith("/api/evaluation"))
            return problem(HttpStatus.BAD_REQUEST, "文档对照评测参数无效，请检查问题和场景。");
        return problem(HttpStatus.BAD_REQUEST, "参数无效：请选择已登记的服务，问题为 1–200 字，窗口须在该服务允许的范围内。");
    }

    @ExceptionHandler(CapacityExceededException.class)
    public ResponseEntity<ProblemDetail> busy() { return problem(HttpStatus.TOO_MANY_REQUESTS, "执行队列或事件连接已满，请稍后再试。"); }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(ProblemDetail.forStatusAndDetail(e.getStatusCode(), e.getReason() == null ? "请求失败。" : e.getReason()));
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, message));
    }
}
