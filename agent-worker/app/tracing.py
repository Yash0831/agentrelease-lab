"""OpenTelemetry setup: correlated traces via W3C traceparent.

Exports to OTLP when OTEL_EXPORTER_OTLP_ENDPOINT is set; otherwise spans are
logged locally. The auditable record is the platform's trace_events table,
independent of this pipeline.
"""
import logging

from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor, ConsoleSpanExporter

from .config import settings

log = logging.getLogger("arl.tracing")
_configured = False


def configure() -> None:
    global _configured
    if _configured:
        return
    provider = TracerProvider()
    endpoint = settings.otel_exporter_otlp_endpoint
    if endpoint:
        try:
            from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
            provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter(endpoint=endpoint)))
            log.info("OTel exporting to %s", endpoint)
        except Exception as e:
            log.warning("OTLP exporter unavailable (%s); using console", e)
            provider.add_span_processor(BatchSpanProcessor(ConsoleSpanExporter()))
    else:
        provider.add_span_processor(BatchSpanProcessor(ConsoleSpanExporter()))
    trace.set_tracer_provider(provider)
    _configured = True


def tracer(name: str = "arl.worker"):
    configure()
    return trace.get_tracer(name)
