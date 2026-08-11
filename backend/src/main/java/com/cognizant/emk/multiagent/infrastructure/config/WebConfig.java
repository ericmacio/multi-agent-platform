package com.cognizant.emk.multiagent.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC configuration.
 *
 * <p>Prepends {@code app.api.base-path} to every {@link RestController} mapping. Controllers
 * therefore declare paths relative to the base path (e.g. {@code @RequestMapping("/agents")})
 * and never repeat {@code /api/v1}.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ApplicationProperties properties;

    public WebConfig(ApplicationProperties properties) {
        this.properties = properties;
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(properties.api().basePath(), c -> c.isAnnotationPresent(RestController.class));
    }
}
