import os

INTERNAL_TOKEN = os.getenv("INTERNAL_TOKEN", "dev-internal-token-change-me")
BACKEND_URL = os.getenv("BACKEND_URL", "http://localhost:8080")
COINDCX_PUBLIC = os.getenv("COINDCX_PUBLIC", "https://public.coindcx.com")
SIGNAL_POLL_SECONDS = int(os.getenv("SIGNAL_POLL_SECONDS", "60"))
