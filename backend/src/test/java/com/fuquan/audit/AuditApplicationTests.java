package com.fuquan.audit;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@SpringBootTest
@ActiveProfiles("local")
class AuditApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    @Test
    void applicationContextLoads() {
    }

    @Test
    void doesNotCreateDefaultUserOrGeneratedPassword() {
        assertThat(applicationContext.getBeansOfType(UserDetailsService.class)).isEmpty();
    }

    @Test
    void localProfileDoesNotCreateDatabaseOrRedisConnections() {
        assertThat(applicationContext.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(RedisConnectionFactory.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(SessionRepository.class)).isEmpty();
    }

    @Test
    void productionContextDoesNotRegisterValidationProbeApi() {
        assertThat(requestMappingHandlerMapping.getHandlerMethods().keySet())
                .noneMatch(mapping -> mapping.getPatternValues().contains("/api/v1/validation-probe"));
    }
}
