package com.agentreleaselab.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenTelemetry: correlated traces via W3C trace context.
 *  Exports to OTLP when OTEL_EXPORTER_OTLP_ENDPOINT is configured; otherwise
 *  spans stay in-process. The auditable record is the trace_events table
 *  (TraceService), independent of this pipeline. */
@Configuration
public class OtelConfig {

    private static final Logger log = LoggerFactory.getLogger(OtelConfig.class);

    @Bean
    public OpenTelemetry openTelemetry(
            @Value("${OTEL_EXPORTER_OTLP_ENDPOINT:}") String endpoint) {
        SdkTracerProvider provider;
        if (endpoint != null && !endpoint.isBlank()) {
            OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                    .setEndpoint(endpoint).build();
            provider = SdkTracerProvider.builder()
                    .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                    .build();
            log.info("OTel exporting spans to {}", endpoint);
        } else {
            provider = SdkTracerProvider.builder().build();
        }
        return OpenTelemetrySdk.builder().setTracerProvider(provider).build();
    }

    @Bean
    public Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("arl.platform");
    }
}
