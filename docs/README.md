# TimeLord Docs

Source for the [docs site](https://johnjoeallen.github.io/timelord/), built with
[MkDocs](https://www.mkdocs.org/) + [Material](https://squidfunk.github.io/mkdocs-material/) and
published to GitHub Pages via `.github/workflows/docs.yml` on every push to `main` that touches
`docs/` or `mkdocs.yml`.

- [`index.md`](index.md) — site home page (overview, screenshots, what's implemented)
- [`architecture.md`](architecture.md) — component diagram, discovery sequence, event
  delivery/idempotency, security boundary, known limitations
- `images/` — screenshots referenced from the pages above

## Previewing locally

```console
$ pip install mkdocs-material mike
$ mkdocs serve
```

Opens a live-reloading preview at `http://127.0.0.1:8000`.

## Publishing

The workflow deploys with [`mike`](https://github.com/jimporter/mike), which keeps built HTML on
the `gh-pages` branch — never hand-edit that branch, it's generated output, not a source. A doc
fix always goes into `docs/` on `main`; the workflow rebuilds and pushes `gh-pages` for you.

A threat model, formal protocol spec, and further operational guides land here as the system
grows past Phase 1.
