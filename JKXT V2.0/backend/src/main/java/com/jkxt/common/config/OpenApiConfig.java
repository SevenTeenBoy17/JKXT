package com.jkxt.common.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
    info = @Info(
        title = "JKXT V2.0 Backend API",
        version = "0.1.0-p1",
        description = "P1 backend skeleton for AI-assisted exam invigilation scheduling."
    )
)
public class OpenApiConfig {
}
