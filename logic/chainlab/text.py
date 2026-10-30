"""Les nombres des phrases du laboratoire, écrits à la française (virgule, puissances de dix en exposant)."""

import math

SUPERSCRIPT = str.maketrans("-0123456789", "⁻⁰¹²³⁴⁵⁶⁷⁸⁹")


def number(value, digits=3):
    """3 chiffres significatifs ; « 1,2 × 10¹⁷ » au-delà de 10⁶ ou en deçà de 10⁻³."""
    if value == 0:
        return "0"
    if not math.isfinite(value):
        return "∞"
    sign = "−" if value < 0 else ""
    magnitude = abs(value)
    if 1e-3 <= magnitude < 1e6:
        text = f"{magnitude:.{digits}g}"
        if "e" in text:
            text = f"{magnitude:.0f}"
        if magnitude >= 1000:
            text = f"{round(magnitude):,}".replace(",", " ")   # espace fine insécable
        return sign + text.replace(".", ",")
    exponent = int(math.floor(math.log10(magnitude)))
    mantissa = magnitude / 10 ** exponent
    power = "10" + str(exponent).translate(SUPERSCRIPT)
    if round(mantissa, digits - 1) == 1:
        return sign + power
    return sign + f"{mantissa:.{digits - 1}f}".rstrip("0").rstrip(".").replace(".", ",") + " × " + power


def fixed(value, decimals):
    return f"{value:.{decimals}f}".replace(".", ",").replace("-", "−")


def joules(j):
    for unit, size in (("PJ", 1e15), ("TJ", 1e12), ("GJ", 1e9), ("MJ", 1e6), ("kJ", 1e3)):
        if j >= size:
            return f"{number(j / size)} {unit}"
    return f"{number(j)} J"


def energy_text(j):
    if j >= 4.184e12:
        return f"{number(j / 4.184e12)} kt de TNT"
    return joules(j)


def percent(fraction):
    return f"{number(100 * fraction)} %"
