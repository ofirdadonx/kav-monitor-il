import arabic_reshaper
from bidi.algorithm import get_display

# --- Logging and Bilingual Helpers ---

def log_step(description):
    """Standardizes step logging in [STEP] - Description format."""
    print(f"\n[STEP] - {description}")

def log_api(url):
    """Standardizes API call logging."""
    print(f"  [API] - Executing call to: {url}")

def format_hebrew(text):
    """Reshapes and applies bidi for correct Hebrew display in terminals."""
    if not text:
        return "N/A"
    return get_display(arabic_reshaper.reshape(str(text)))
