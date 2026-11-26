"""Decode GS1 DataMatrix payloads from QRbot into validated structured fields.

Supports (01)/(17)/(10)/(21) and compact GS1 with ASCII 29 separators.
Variable-length AIs require separators unless they are the last field.
"""
from __future__ import annotations

import calendar
from datetime import date
import re

GS = "\x1d"
REQUIRED = {"01", "10", "17", "21"}
MARKERS = re.compile(r"\((01|10|17|21)\)")


class GS1Error(ValueError):
    pass


def _normalize(text: str) -> str:
    text = text.rstrip("\r\n")
    for marker in ("<gs>", "[gs]", "<fnc1>"):
        text = re.sub(re.escape(marker), lambda _: GS, text, flags=re.IGNORECASE)
    for marker in (r"\x1D", r"\x1d", r"\u001D", r"\u001d"):
        text = text.replace(marker, GS)
    if text[:3].lower() == "]d2":
        text = text[3:]
    text = text.removeprefix(GS)
    if text[:3].lower() == "]d2":
        text = text[3:]
    return text


def _fields(text: str) -> dict[str, str]:
    pairs: list[tuple[str, str]] = []
    if text.startswith("("):
        matches = list(MARKERS.finditer(text))
        if not matches or matches[0].start() != 0:
            raise GS1Error("Structure GS1 entre parentheses incorrecte")
        for i, match in enumerate(matches):
            end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
            pairs.append((match.group(1), text[match.end():end].removesuffix(GS)))
    else:
        i = 0
        while i < len(text):
            if text[i] == GS or i + 2 > len(text):
                raise GS1Error("Separateur GS ou AI incomplet")
            ai = text[i:i + 2]
            i += 2
            if ai in ("01", "17"):
                width = 14 if ai == "01" else 6
                value = text[i:i + width]
                if len(value) != width or not value.isascii() or not value.isdigit():
                    raise GS1Error(f"AI {ai}: {width} chiffres attendus")
                i += width
                if i < len(text) and text[i] == GS:
                    i += 1
            elif ai in ("10", "21"):
                end = text.find(GS, i)
                if end == -1:
                    end = len(text)
                value = text[i:end]
                i = end + 1 if end < len(text) else end
            else:
                raise GS1Error(f"AI inconnu: {ai}. Verifier les separateurs ASCII 29")
            pairs.append((ai, value))
    keys = [key for key, _ in pairs]
    if len(keys) != len(set(keys)):
        raise GS1Error("AI GS1 duplique")
    result = dict(pairs)
    if missing := REQUIRED - result.keys():
        raise GS1Error("Champs manquants: " + ", ".join(sorted(missing)) + ". Verifier les separateurs GS ASCII 29")
    return result


def validate_fields(value: dict) -> dict[str, str]:
    """Validate pre-separated values and return the canonical JSON for Scala."""
    if not isinstance(value, dict):
        raise GS1Error("Objet JSON attendu")
    gtin, lot, serial, expiry = (value.get(k) for k in ("gtin", "lot", "serial", "expiration"))
    if not isinstance(gtin, str) or not re.fullmatch(r"[0-9]{14}", gtin):
        raise GS1Error("GTIN: 14 chiffres attendus")
    for name, item in (("lot", lot), ("serial", serial)):
        if not isinstance(item, str) or not 1 <= len(item) <= 20 or GS in item:
            raise GS1Error(f"{name}: 1 a 20 caracteres, sans separateur GS")
    if not isinstance(expiry, str):
        raise GS1Error("Expiration: date ISO YYYY-MM-DD attendue")
    try:
        parsed = date.fromisoformat(expiry)
    except ValueError as exc:
        raise GS1Error("Expiration: date ISO invalide") from exc
    if not 2000 <= parsed.year <= 2099:
        raise GS1Error("Expiration hors plage 2000-2099")
    return {"gtin": gtin, "lot": lot, "serial": serial, "expiration": parsed.isoformat()}


def decode_gs1(code: str) -> dict[str, str]:
    if not isinstance(code, str) or not code:
        raise GS1Error("Code GS1 absent")
    fields = _fields(_normalize(code))
    raw_expiry = fields["17"]
    if not re.fullmatch(r"[0-9]{6}", raw_expiry):
        raise GS1Error("Date GS1: AAMMJJ attendu")
    year = 2000 + int(raw_expiry[:2])
    month, day = int(raw_expiry[2:4]), int(raw_expiry[4:6])
    try:
        if day == 0:
            day = calendar.monthrange(year, month)[1]
        expiry = date(year, month, day)
    except (ValueError, calendar.IllegalMonthError) as exc:
        raise GS1Error("Date GS1 invalide") from exc
    return validate_fields({"gtin": fields["01"], "lot": fields["10"], "serial": fields["21"], "expiration": expiry.isoformat()})
