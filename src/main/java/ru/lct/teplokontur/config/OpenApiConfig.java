package ru.lct.teplokontur.config;
import io.swagger.v3.oas.models.OpenAPI;import io.swagger.v3.oas.models.info.Info;import org.springframework.context.annotation.*;
@Configuration public class OpenApiConfig {@Bean public OpenAPI openAPI(){return new OpenAPI().info(new Info().title("TeploKontur API").version("1.0.0").description("Automatic 2D/3D heat-network routing. Java 11, streaming GeoJSON, three ranked variants."));}}
