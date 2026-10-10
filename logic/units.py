"""Mise en forme des grandeurs pour l'affichage."""

import math

YEAR_S = 365.25 * 86400.0


def _number(value):
    """3 chiffres significatifs, en notation française, sans exposant."""
    if value >= 1000:
        return f"{round(value):,}".replace(",", " ")
    return f"{value:.3g}".replace(".", ",")


def format_duration(seconds):
    """Une durée lisible, de 10⁻²² s au milliard d'années."""
    if seconds is None or not math.isfinite(seconds):
        return "infinie"
    if seconds <= 0:
        return "0 s"
    if 1e-15 <= seconds < 1e-12:
        return f"{_number(seconds * 1e15)} fs"
    if 1e-12 <= seconds < 1e-9:
        return f"{_number(seconds * 1e12)} ps"
    if seconds < 1e-9:
        exponent = math.floor(math.log10(seconds))
        mantissa = seconds / 10 ** exponent
        return f"{mantissa:.1f} × 10^{exponent} s".replace(".", ",")
    for limit, unit, scale in ((1e-6, "ns", 1e-9), (1e-3, "µs", 1e-6), (1, "ms", 1e-3),
                               (120, "s", 1), (7200, "min", 60), (2 * 86400, "h", 3600),
                               (2 * YEAR_S, "jours", 86400), (2e6 * YEAR_S, "ans", YEAR_S),
                               (2e9 * YEAR_S, "millions d'années", 1e6 * YEAR_S)):
        if seconds < limit:
            return f"{_number(seconds / scale)} {unit}"
    return f"{_number(seconds / (1e9 * YEAR_S))} milliards d'années"
