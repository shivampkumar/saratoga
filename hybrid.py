"""Cloud fallback when Cactus flags `cloud_handoff`."""
import os


def gemini_fallback(prompt: str) -> str:
    if not os.environ.get("GEMINI_API_KEY"):
        return "[cloud unavailable: set GEMINI_API_KEY]"
    try:
        from google import genai
    except ImportError:
        return "[install google-genai]"
    client = genai.Client()
    r = client.models.generate_content(model="gemini-2.5-flash", contents=prompt)
    return r.text or ""
