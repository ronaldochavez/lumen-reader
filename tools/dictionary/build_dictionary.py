"""Builds Lumen's offline dictionaries (app/src/main/assets/dictionary/{en,es}.db).

Sources (downloaded into tools/dictionary/work/, which isn't committed):
  English: Open English WordNet 2025, WNDB format
           https://github.com/globalwordnet/english-wordnet/releases (english-wordnet-2025.zip, unzipped)
           License: WordNet License + CC BY 4.0 (attribute Princeton WordNet and the Open English Wordnet team).
  Spanish: Spanish Wiktionary, extracted by wiktextract / kaikki.org
           https://kaikki.org/eswiktionary/raw-wiktextract-data.jsonl.gz
           License: CC BY-SA 4.0 (Wiktionary contributors).

Both databases share one schema, which the app's DictionaryRepository reads:
  glosses(id, pos, definition, examples)   examples: "\n"-separated
  senses(word, gloss, rank)                word: lowercase headword; rank: 1 = most common sense
  forms(form, word)                        inflected/irregular form -> headword (went -> go, hablé -> hablar)

Usage: python tools/dictionary/build_dictionary.py [en] [es]
"""
import gzip
import json
import pathlib
import re
import sqlite3
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
WORK = ROOT / "tools" / "dictionary" / "work"
OUT = ROOT / "app" / "src" / "main" / "assets" / "dictionary"

SCHEMA = """
CREATE TABLE glosses (id INTEGER PRIMARY KEY, pos TEXT NOT NULL, definition TEXT NOT NULL, examples TEXT NOT NULL);
CREATE TABLE senses (word TEXT NOT NULL, gloss INTEGER NOT NULL, rank INTEGER NOT NULL);
CREATE TABLE forms (form TEXT NOT NULL, word TEXT NOT NULL);
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
"""
INDEXES = """
CREATE INDEX senses_word ON senses(word, rank);
CREATE INDEX forms_form ON forms(form);
"""


def new_db(name):
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / f"{name}.db"
    path.unlink(missing_ok=True)
    db = sqlite3.connect(path)
    db.executescript(SCHEMA)
    return db, path


def finish(db, path, source, license_text):
    db.executemany("INSERT INTO meta VALUES (?, ?)", [("source", source), ("license", license_text)])
    db.executescript(INDEXES)
    db.commit()
    db.execute("VACUUM")
    db.close()
    con = sqlite3.connect(path)
    words = con.execute("SELECT COUNT(DISTINCT word) FROM senses").fetchone()[0]
    glosses = con.execute("SELECT COUNT(*) FROM glosses").fetchone()[0]
    forms = con.execute("SELECT COUNT(*) FROM forms").fetchone()[0]
    con.close()
    print(f"{path.name}: {words:,} words, {glosses:,} definitions, {forms:,} forms, {path.stat().st_size / 2**20:.1f} MB")


# ---------------------------------------------------------------- English (WordNet)

WN_POS = {"n": "noun", "v": "verb", "a": "adjective", "s": "adjective", "r": "adverb"}
WN_FILES = {"noun": "data.noun", "verb": "data.verb", "adjective": "data.adj", "adverb": "data.adv"}
WN_SS_TYPE = {"1": "noun", "2": "verb", "3": "adjective", "4": "adverb", "5": "adjective"}


def split_gloss(gloss):
    """'make a face; "She winced"; "another"' -> ('make a face', ['She winced', 'another'])."""
    examples = re.findall(r'"([^"]+)"', gloss)
    definition = re.sub(r'\s*;?\s*"[^"]*"', "", gloss).strip().rstrip(";").strip()
    return definition, examples


def build_english():
    wn = WORK / "oewn2025"
    db, path = new_db("en")
    ids = {}  # (pos, offset) -> gloss id
    for pos, fname in WN_FILES.items():
        for line in (wn / fname).read_text(encoding="utf-8").splitlines():
            if line.startswith("  ") or "|" not in line:
                continue  # license header
            head, gloss = line.split("|", 1)
            offset = head.split()[0]
            definition, examples = split_gloss(gloss.strip())
            cur = db.execute("INSERT INTO glosses (pos, definition, examples) VALUES (?, ?, ?)",
                             (pos, definition, "\n".join(examples)))
            ids[(pos, offset)] = cur.lastrowid
    # Order a word's senses across parts of speech: the part of speech with the most senses first (OEWN 2025 has no
    # usage counts; "go" has 30 verb senses and 4 noun ones), then WordNet's own sense order within it.
    by_word = {}
    for line in (wn / "index.sense").read_text(encoding="utf-8").splitlines():
        key, offset, sense_number, _ = line.split()
        lemma, rest = key.split("%", 1)
        pos = WN_SS_TYPE[rest[0]]
        gloss = ids.get((pos, offset))
        if gloss:
            by_word.setdefault(lemma.replace("_", " ").lower(), {}).setdefault(pos, set()).add((int(sense_number), gloss))
    rows = []
    for word, by_pos in by_word.items():
        ordered = sorted(by_pos.items(), key=lambda kv: (-len(kv[1]), kv[0]))
        rank = 0
        for _, items in ordered:
            for _, gloss in sorted(items):
                rank += 1
                rows.append((word, gloss, rank))
    db.executemany("INSERT INTO senses VALUES (?, ?, ?)", rows)
    forms = set()
    for exc in ("noun.exc", "verb.exc", "adj.exc", "adv.exc"):
        for line in (wn / exc).read_text(encoding="utf-8").splitlines():
            parts = line.split()
            for base in parts[1:]:
                forms.add((parts[0].replace("_", " "), base.replace("_", " ")))
    db.executemany("INSERT INTO forms VALUES (?, ?)", sorted(forms))
    finish(db, path, "Open English WordNet 2025 (en-word.net), derived from Princeton WordNet 3.0",
           "WordNet License; CC BY 4.0 — Princeton University and the Open English Wordnet team")


# ---------------------------------------------------------------- Spanish (Wiktionary)

ES_POS = {
    "noun": "noun", "name": "proper noun", "verb": "verb", "adj": "adjective", "adv": "adverb",
    "pron": "pronoun", "prep": "preposition", "conj": "conjunction", "intj": "interjection", "det": "determiner",
    "article": "article", "num": "numeral", "phrase": "phrase", "proverb": "proverb", "contraction": "contraction",
    "affix": None, "suffix": None, "prefix": None, "character": None, "symbol": None, "abbrev": "abbreviation",
}
MAX_EXAMPLES = 2


def build_spanish():
    db, path = new_db("es")
    rank_of = {}  # word -> next rank
    senses, forms = [], set()
    with gzip.open(WORK / "es-wiktextract.jsonl.gz", "rt", encoding="utf-8") as f:
        for line in f:
            entry = json.loads(line)
            if entry.get("lang_code") != "es" or not entry.get("word"):
                continue
            word = entry["word"].strip()
            key = word.lower()
            pos = ES_POS.get(entry.get("pos", ""), entry.get("pos"))
            if pos is None:
                continue
            # A headword's inflection table ("hablar": hablo, hablaba, hablé…) maps every form back to it.
            for form in entry.get("forms", []):
                text = (form.get("form") or "").strip().lower()
                tags = form.get("tags", [])
                if (text and text != key and " " not in text and not text.startswith("-")
                        and "table-tags" not in tags and "inflection-template" not in tags):
                    forms.add((text, key))
            for sense in entry.get("senses", []):
                # "Forma del plural de casa": map the form to its headword; the gloss itself adds nothing.
                targets = [(t.get("word") or "").strip().lower() for t in sense.get("form_of", [])]
                if targets:
                    forms.update((key, base) for base in targets if base and base != key)
                    continue
                glosses = [g.strip() for g in sense.get("glosses", []) if g and g.strip()]
                if not glosses:
                    continue
                examples = [e.get("text", "").strip() for e in sense.get("examples", []) if e.get("text")][:MAX_EXAMPLES]
                cur = db.execute("INSERT INTO glosses (pos, definition, examples) VALUES (?, ?, ?)",
                                 (pos, " ".join(glosses), "\n".join(examples)))
                rank_of[key] = rank_of.get(key, 0) + 1
                senses.append((key, cur.lastrowid, rank_of[key]))
    db.executemany("INSERT INTO senses VALUES (?, ?, ?)", senses)
    # Only forms the app's rules (es_rules.json) can't derive: ~5% of them (dijo -> decir, tatúo -> tatuar).
    sys.path.insert(0, str(pathlib.Path(__file__).parent))
    from es_morphology import candidates
    exceptions = sorted((f, w) for f, w in forms if w not in set(candidates(f)))
    print(f"es forms: {len(forms):,} in Wiktionary, {len(exceptions):,} irregular ones stored")
    db.executemany("INSERT INTO forms VALUES (?, ?)", exceptions)
    finish(db, path, "Spanish Wiktionary (es.wiktionary.org), extracted with wiktextract (kaikki.org)",
           "CC BY-SA 4.0 — Wiktionary contributors")


if __name__ == "__main__":
    langs = sys.argv[1:] or ["en", "es"]
    if "en" in langs:
        build_english()
    if "es" in langs:
        build_spanish()
