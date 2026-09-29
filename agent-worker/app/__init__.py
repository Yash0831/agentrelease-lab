"""AgentRelease Lab — agent execution & evaluation worker.

The worker drives the agent loop (fixture or live LLM), calls business tools
exclusively through the platform's tool gateway, records trace events, and
computes deterministic evaluation metrics.
"""
