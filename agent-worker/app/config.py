"""Worker configuration (env-driven; no secrets committed)."""
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    platform_base_url: str = "http://localhost:8080"
    platform_api_key: str = "arl-acme-agent-demo"  # demo default; override in env
    redis_url: str = "redis://localhost:6379/0"
    worker_api_key: str = "change-me-worker-key"

    llm_base_url: str = ""      # OpenAI-compatible endpoint; blank => fixture only
    llm_api_key: str = ""
    llm_model: str = ""
    llm_timeout_seconds: int = 60

    embedding_mode: str = "fixture"
    otel_exporter_otlp_endpoint: str = ""

    max_agent_steps: int = 12
    llm_max_retries: int = 3

    model_config = {"env_prefix": ""}


settings = Settings()
