"""Tool registry. Add tool schemas + handlers per vertical."""

TOOLS: list[dict] = []
HANDLERS: dict = {}


def register(schema, handler):
    TOOLS.append(schema)
    HANDLERS[schema["function"]["name"]] = handler


def dispatch(call):
    """call: {"name": str, "arguments": dict}. Returns str result."""
    name = call.get("name")
    args = call.get("arguments", {})
    fn = HANDLERS.get(name)
    if not fn:
        return f"unknown tool: {name}"
    try:
        return str(fn(**args))
    except Exception as e:
        return f"tool error: {e}"
