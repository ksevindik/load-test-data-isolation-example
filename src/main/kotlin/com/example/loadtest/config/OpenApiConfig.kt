package com.example.loadtest.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.parameters.Parameter
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.*

@Configuration
class OpenApiConfig {

    @Bean
    fun customOpenAPI(): OpenAPI {
        return OpenAPI()
            .info(
                Info()
                    .title("Load Test Data Isolation API")
                    .version("1.0")
                    .description("API with RLS-based test data isolation. Use X-Traffic-Type: LOAD_TEST header to access test data.")
            )
    }

    @Bean
    fun globalHeaderCustomizer(): OperationCustomizer {
        return OperationCustomizer { operation, _ ->
            operation.addParametersItem(
                Parameter()
                    .`in`("header")
                    .name("X-Test-Run-Id")
                    .description("Unique identifier for the test run. Auto-generated UUID if not provided.")
                    .required(false)
                    .schema(
                        io.swagger.v3.oas.models.media.StringSchema()
                            .example(UUID.randomUUID().toString())
                    )
            )
            operation.addParametersItem(
                Parameter()
                    .`in`("header")
                    .name("X-Traffic-Type")
                    .description("Set to LOAD_TEST to access test data, leave empty for production data")
                    .required(false)
                    .schema(io.swagger.v3.oas.models.media.StringSchema().addEnumItem("LOAD_TEST"))
            )
            operation
        }
    }
}
