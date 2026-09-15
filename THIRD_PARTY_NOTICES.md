# Third-Party Notices

CafeERP itself is MIT-licensed — see [LICENSE](LICENSE). It also uses the
third-party components below, which remain under their own licenses.

## 1. Bundled (redistributed) — full license text included

The following files are verbatim, unmodified copies of third-party build output
committed to this repository:

| File | Component | Version | License | Copyright |
|---|---|---|---|---|
| `franken-core.iife.js` | [Franken UI](https://www.franken-ui.dev) | 2.1.2 | MIT | © Franken UI contributors (author: Reden) |
| `franken-core.min.css` | [Franken UI](https://www.franken-ui.dev) | 2.1.2 | MIT | © Franken UI contributors (author: Reden) |
| `franken-utilities.min.css` | [Franken UI](https://www.franken-ui.dev) | 2.1.2 | MIT | © Franken UI contributors (author: Reden) |

These copies are **not served at runtime**. At runtime the browser loads Franken
UI from jsDelivr (see section 2). The root-level copies are kept in-repo purely
as the provenance source for `css_classes.txt` and
`docs/FRANKEN_UI_CLASS_REFERENCE.md`, which document which utility classes exist
in this exact build.

The MIT License requires that the copyright notice and permission notice be
preserved in all copies, and the upstream build output ships without a license
header, so the full notice is reproduced here:

### Franken UI — MIT License

Copyright (c) Franken UI contributors (author: Reden)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## 2. Loaded from CDN at runtime (not redistributed) — attribution only

These are fetched by the browser from jsDelivr and are **not** redistributed by
this repository, so only attribution is recorded here. See
`src/main/resources/templates/fragments/layout.html`.

| Component | Reference in `layout.html` | Version | License | Copyright |
|---|---|---|---|---|
| [Franken UI](https://www.franken-ui.dev) | `franken-ui@2.1.2` | 2.1.2 (pinned) | MIT | © Franken UI contributors (author: Reden) |
| [marked](https://marked.js.org) | `marked/marked.min.js` | **unpinned** (18.x when this file was written) | MIT | © Christopher Jeffrey and the marked contributors |
| [DOMPurify](https://github.com/cure53/DOMPurify) | `dompurify/dist/purify.min.js` | **unpinned** (3.x when this file was written) | `(MPL-2.0 OR Apache-2.0)` — dual, choose either | © Dr.-Ing. Mario Heiderich, Cure53 |

Note that `marked` and `dompurify` are requested **without a version or a
subresource-integrity hash**, so whatever jsDelivr currently serves as latest is
what runs. Pinning both (plus optional SRI) would remove that floating
dependency and is tracked as a follow-up improvement.

## 3. Dependencies

Java dependencies (Spring Boot, PostgreSQL JDBC, Flyway, and so on) are declared
in `pom.xml` and are not redistributed in source form here; their licenses are
resolved by Maven per artifact.