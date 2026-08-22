package com.prayerroster.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.ByteArrayOutputStream;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

/**
 * Renders a Thymeleaf template to PDF bytes via openhtmltopdf (see docs/phase1-architecture.md
 * section 13). openhtmltopdf parses its input as strict XML, unlike a browser - every template
 * rendered through this service must be well-formed XHTML (every tag explicitly closed).
 */
@Service
public class PdfRenderingService {

    private final SpringTemplateEngine templateEngine;

    public PdfRenderingService(SpringTemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    public byte[] render(String templateName, Context context) {
        try {
            String html = templateEngine.process(templateName, context);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render PDF from template '" + templateName + "'", e);
        }
    }
}
