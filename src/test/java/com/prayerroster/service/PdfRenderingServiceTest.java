package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class PdfRenderingServiceTest {

    private static SpringTemplateEngine realTemplateEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    private final PdfRenderingService service = new PdfRenderingService(realTemplateEngine());

    @Test
    void render_producesRealPdfBytes() {
        Context context = new Context();
        context.setVariable("title", "My Assignments");
        context.setVariable("rows", List.of());

        byte[] pdf = service.render("pdf/assignments", context);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void render_wrapsFailureWhenTemplateDoesNotExist() {
        Context context = new Context();

        assertThatThrownBy(() -> service.render("pdf/does-not-exist", context)).isInstanceOf(IllegalStateException.class);
    }
}
