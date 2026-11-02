package com.example.lancerpick.common;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

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
}
