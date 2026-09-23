"""``python -m app`` starts the engine on loopback, the way the control plane runs in dev.

AICOS_ENGINE_HOST opens it on purpose (for example 0.0.0.0 inside a container).
"""
import os

import uvicorn

from app.config import Settings
from app.main import create_app


def main() -> None:
    settings = Settings.from_environment()
    uvicorn.run(create_app(settings),
                host=os.environ.get("AICOS_ENGINE_HOST", "127.0.0.1"),
                port=int(os.environ.get("AICOS_ENGINE_PORT", "8090")),
                log_level=os.environ.get("AICOS_ENGINE_LOG_LEVEL", "info"))


if __name__ == "__main__":
    main()
