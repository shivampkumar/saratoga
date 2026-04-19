"""Voice agent — Gemma 4 E4B via Cactus FFI."""
import json
import sys
from pathlib import Path

CACTUS_ROOT = Path(__file__).resolve().parents[1] / "cactus"
sys.path.insert(0, str(CACTUS_ROOT / "python"))

from src.cactus import (  # type: ignore
    cactus_complete,
    cactus_destroy,
    cactus_init,
    cactus_reset,
)
from src.downloads import ensure_model  # type: ignore


class VoiceAgent:
    def __init__(self, model_id="google/gemma-4-E4B-it", system=None, tools=None):
        weights = ensure_model(model_id)
        self.model = cactus_init(str(weights), None, False)
        self.system = system or "You are a helpful voice assistant. Reply concisely."
        self.tools = tools
        self.history = []

    def turn(self, user_text=None, pcm=None, max_tokens=128, temperature=0.7):
        """One turn. Pass `user_text` OR raw PCM16 audio `pcm`."""
        user_content = user_text if user_text is not None else "[audio]"
        user_msg = {"role": "user", "content": user_content}
        msgs = [{"role": "system", "content": self.system}, *self.history, user_msg]
        opts = json.dumps({"max_tokens": max_tokens, "temperature": temperature})
        tools_json = json.dumps(self.tools) if self.tools else None
        pcm_list = list(pcm) if pcm else None
        raw = cactus_complete(
            self.model, json.dumps(msgs), opts, tools_json, None, pcm_list
        )
        result = json.loads(raw)
        if result.get("success"):
            self.history.append(user_msg)
            self.history.append(
                {"role": "assistant", "content": result.get("response", "")}
            )
        return result

    def reset(self):
        cactus_reset(self.model)
        self.history.clear()

    def close(self):
        cactus_destroy(self.model)
