package in.boss.shared.web;

import in.boss.shared.tenant.SuspendedTenantInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class BossWebMvcConfig implements WebMvcConfigurer {

    private final SuspendedTenantInterceptor suspendedTenantInterceptor;

    public BossWebMvcConfig(SuspendedTenantInterceptor suspendedTenantInterceptor) {
        this.suspendedTenantInterceptor = suspendedTenantInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(suspendedTenantInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/actuator/**");
    }
}
