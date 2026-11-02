package com.example.lancerpick.common;

import com.example.lancerpick.product.service.CategoryService;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 기본 프로필에도 공개 메인이 동작하는지 동일한 HTTP·Security 계약을 검사한다. */
@ActiveProfiles(value = "default", inheritProfiles = false)
@SpringBootTest(properties = {
        // 개인 접속 파일은 읽지 않는다. DB 제외는 이 테스트 컨텍스트에만 적용한다.
        "spring.config.location=optional:classpath:/default-catalog-test.properties",
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration,"
                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
class DefaultCatalogApiTests extends CommonApiTests {
    // DB 없는 공통 HTTP 검사에서는 실제 카테고리 DB 조회를 사용하지 않는다.
    @MockitoBean
    private CategoryService categoryService;
}
