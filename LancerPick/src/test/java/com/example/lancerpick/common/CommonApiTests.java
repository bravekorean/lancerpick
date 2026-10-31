package com.example.lancerpick.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** DB가 없는 demo 프로필에서 공통 HTTP·Security 계약만 검사한다. 로그인 구현 테스트는 아니다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Import(CommonApiTests.ProbeController.class)
class CommonApiTests {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;

    @Test
    void publicCatalogFiltersAndPaginates() throws Exception {
        mvc.perform(get("/api/demo/products").param("size", "4"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.totalElements").value(8)).andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/api/demo/products").param("keyword", "웹사이트").param("categoryId", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].category.id").value("2"));
        mvc.perform(get("/api/demo/products").param("page", "2147483647"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void invalidQueryHasSharedErrorAndTraceId() throws Exception {
        var result = mvc.perform(get("/api/demo/products").param("size", "0"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors").isArray()).andReturn();
        String trace = mapper.readTree(result.getResponse().getContentAsString()).get("traceId").asText();
        assertThat(trace).isEqualTo(result.getResponse().getHeader("X-Trace-Id"));
        mvc.perform(get("/api/demo/products").param("page", "wrong"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void anonymousProtectedRequestReturnsJsonWithoutRedirect() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(header().doesNotExist("Location")).andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @WithMockUser(roles = "USER")
    void userCannotAccessAdmin() throws Exception {
        mvc.perform(get("/api/admin/products"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void missingCsrfReturnsSharedError() throws Exception {
        mvc.perform(post("/api/members"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void csrfEndpointTokenWorksWithItsSession() throws Exception {
        var result = mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.token").isNotEmpty()).andReturn();
        var json = mapper.readTree(result.getResponse().getContentAsString());
        var session = (MockHttpSession) result.getRequest().getSession(false);
        // 인증이 없는 보호 경로: CSRF가 통과하면 403이 아닌 401이다.
        mvc.perform(post("/api/probe/write").session(session)
                        .header(json.get("headerName").asText(), json.get("token").asText()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @WithMockUser
    void authenticatedMutationWithCsrfCanReturn204() throws Exception {
        mvc.perform(post("/api/probe/write").with(csrf()))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
    }

    @Test
    void unknownPublicDemoResourceReturns404() throws Exception {
        mvc.perform(get("/api/demo/not-found"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @RestController
    public static class ProbeController {
        @PostMapping("/api/probe/write")
        public org.springframework.http.ResponseEntity<Void> write() {
            return org.springframework.http.ResponseEntity.noContent().build();
        }
    }
}
