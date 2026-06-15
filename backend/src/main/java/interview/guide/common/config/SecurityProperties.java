package interview.guide.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    private JwtConfig jwt = new JwtConfig();

    @Data
    public static class JwtConfig {
        private String secret;
        private long accessTokenValidityMs = 3_600_000;
        private long refreshTokenValidityMs = 2_592_000_000L;
    }
}
