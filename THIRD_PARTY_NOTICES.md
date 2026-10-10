# Third-party data

Lumen's offline dictionaries (`app/src/main/assets/dictionary/`) are built from open data by
`tools/dictionary/build_dictionary.py`.

## English: Open English WordNet 2025

`en.db` is derived from [Open English WordNet](https://en-word.net/) (2025 edition), which is derived from
Princeton WordNet 3.0.

- Open English WordNet: © the Open English Wordnet team, [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
- Princeton WordNet: © 2006 Princeton University, under the
  [WordNet License](https://wordnet.princeton.edu/license-and-commercial-use).

> WordNet 3.0 Copyright 2006 by Princeton University. All rights reserved. THIS SOFTWARE AND DATABASE IS PROVIDED
> "AS IS" AND PRINCETON UNIVERSITY MAKES NO REPRESENTATIONS OR WARRANTIES, EXPRESS OR IMPLIED. The name of Princeton
> University or Princeton may not be used in advertising or publicity pertaining to distribution of the software
> and/or database.

## Spanish: Wiktionary en español

`es.db` contains definitions and examples from [Wiktionary en español](https://es.wiktionary.org/), extracted with
[wiktextract](https://github.com/tatuylonen/wiktextract) as published by [kaikki.org](https://kaikki.org/eswiktionary/).

- © Wiktionary contributors, [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).
- Changes: filtered to Spanish entries; inflected forms that `es_rules.json` can derive were removed, the rest are
  kept as a lookup table. `es.db` is distributed under the same CC BY-SA 4.0 license (this applies to the data only,
  not to Lumen's code, which is MIT).
