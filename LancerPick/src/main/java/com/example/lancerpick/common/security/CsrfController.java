package com.example.lancerpick.common.security;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CsrfController {
    @GetMapping("/api/auth/csrf")
    public ResponseEntity<CsrfResponse> csrf(CsrfToken csrfToken) {
        // 기본 XOR 요청 핸들러가 노출하는 토큰을 그대로 헤더에 제출한다. 쿠키에서 토큰을 읽지 않는다.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CsrfResponse(csrfToken.getToken(), csrfToken.getHeaderName()));
    }

    public record CsrfResponse(String token, String headerName) {}
}
