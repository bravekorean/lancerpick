package com.example.lancerpick.common.security;

import com.example.lancerpick.common.exception.ApiError;
import com.example.lancerpick.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 필터에서 발생한 오류는 ControllerAdvice를 거치지 않으므로 같은 DTO로 직접 쓴다. */
@Component
public class SecurityErrorWriter {
    private final JsonMapper mapper;

    public SecurityErrorWriter(JsonMapper mapper) { this.mapper = mapper; }

    public void write(HttpServletRequest request, HttpServletResponse response,
            int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(mapper.writeValueAsString(
                new ApiError(code, message, List.of(), TraceIdFilter.traceId(request))));
    }
}
