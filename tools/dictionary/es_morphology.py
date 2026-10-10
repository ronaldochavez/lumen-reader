"""Python twin of the app's SpanishMorphology (same rules file): candidate headwords for an inflected Spanish word."""
import json
import pathlib

RULES = json.loads((pathlib.Path(__file__).resolve().parents[2] / "app/src/main/assets/dictionary/es_rules.json")
                   .read_text(encoding="utf-8"))
UNACCENT = str.maketrans("áéíóú", "aeiou")


def candidates(word):
    """Every headword the rules can derive from `word` (not checked against the dictionary). Order: closest first."""
    word = word.lower()
    stems = [word]
    # Attached pronouns: "dígamelo" -> "díga" -> "diga" (the accent only marks stress once the pronoun is added).
    for _ in range(2):
        for s in list(stems):
            for clitic in RULES["clitics"]:
                if s.endswith(clitic) and len(s) - len(clitic) >= 3:
                    base = s[: -len(clitic)]
                    for b in (base, base.translate(UNACCENT)):
                        if b not in stems:
                            stems.append(b)
    out = []
    for s in stems:
        for infinitive, endings in RULES["verb_endings"].items():
            for ending in endings:
                if s.endswith(ending) and len(s) - len(ending) >= 1:
                    stem = s[: -len(ending)]
                    variants = [stem]
                    for old, new in RULES["stem_changes"]:
                        i = stem.rfind(old)
                        if i >= 0:
                            variants.append(stem[:i] + new + stem[i + len(old):])
                    for v in variants:
                        if v:
                            out.append(v + infinitive)
                            out.append(v + infinitive + "se")
        for suffix, replacement in RULES["nominal"]:
            if s.endswith(suffix) and len(s) - len(suffix) >= 2:
                out.append(s[: -len(suffix)] + replacement)
    seen, ordered = set(), []
    for c in stems[1:] + out:
        if c != word and c not in seen:
            seen.add(c)
            ordered.append(c)
    return ordered
