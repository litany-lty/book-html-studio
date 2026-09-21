package studio.bookhtml.decision;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import studio.bookhtml.config.DecisionProperties;

/**
 * J07：决策传输装配。Mock 只在 provider=MOCK 的测试 profile 下装配，绝不与真实通道混记。
 */
@Configuration
public class DecisionBeans {
    @Bean(destroyMethod = "close")
    public SharedTransport sharedTransport() {
        return new SharedTransport();
    }

    @Bean
    @Primary
    public DecisionTransport decisionTransport(DecisionProperties properties,
                                               SharedTransport shared) {
        if ("MOCK".equalsIgnoreCase(properties.getProvider())) return new MockDecisionTransport();
        return shared;
    }
}
