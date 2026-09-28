---
title: Writing documents
description: Document files, versions, pages, translations, and allowed formatting.
order: 4
---

# Writing documents

Each document is one `.yml` file in the `documents/` folder. The plugin creates two inactive starters, `terms.yml.example` and `privacy.yml.example`, with English and Indonesian text. Review them, replace every bracketed placeholder, and rename them to `.yml` to use them.

## Example

```yaml
id: community-rules
version: "2026-09"
required: true
order: 10
translations:
  en-US:
    title: "<gold><bold>Community Rules</bold></gold>"
    summary: "<gray>Please review the rules before joining.</gray>"
    checkbox: "I accept the <gold>Community Rules</gold>"
    read-button: "Read the full rules"
    pages:
      - title: "Playing together"
        body: |-
          <white>Write the full document here.</white>

          <gray>This is example text for configuration only.</gray>
      - title: "Questions and changes"
        body: |-
          <white>Add another page if needed.</white>
```

| Field | Meaning |
| --- | --- |
| `id` | Identifies the document. Keep it stable; it does not depend on the file name or title. |
| `version` | A quoted text version, like `"2026-09"` or `"v3"`. Compared exactly, not as a number. |
| `required` | `true` shows the document on the consent screen. `false` hides it. |
| `order` | Position on the consent screen. |
| `translations` | One entry per language, each with its own title, summary, checkbox label, Read button label, and pages. |

## Versions

Change the version whenever you edit an active or previously accepted document. This includes titles, summaries, labels, page text, page breaks, and formatting, even a spelling fix or a color tag. Players accept the new version on their next connection.

Reload rejects edited text under an unchanged version, even if nobody has accepted it yet. This keeps every saved acceptance tied to the exact text the player saw.

Changing the global `appearance` colors or the document order does not need a new version.

If several servers share consent, update their document files together so the same ID and version never mean different text.

## Required and optional

Only `required: true` documents appear on the consent screen, and at least one is needed to enable the gate. `required: false` hides a document. Optional checkboxes and information-only pages are not supported. Hidden files are still loaded and validated.

## Formatting

Document text uses a restricted set of [MiniMessage](https://docs.advntr.dev/minimessage/format.html) tags:

- Named colors and hex colors, like `<gold>` or `<#12abef>`
- `<bold>`, `<italic>`, `<underlined>`, `<strikethrough>`, `<obfuscated>`

Tags must be closed. Click, hover, insertion, font, gradient, rainbow, and link tags are rejected. Use YAML line breaks, not tags, for new lines.

On Bedrock, underline and strikethrough are left out, since their formatting codes mean colors there.

## Limits

| Item | Limit |
| --- | --- |
| Document files | Up to 32 `.yml`/`.yaml` files directly in the folder. Subfolders and `.example` files are not loaded. |
| Per document | Up to 32 translations, 32 pages per translation, and 256 KiB per file |
| `id` | 1 to 64 characters: lowercase letters, numbers, `_`, `-`; starts with a letter or number |
| `version` | 1 to 64 characters, quoted |

Opening a document is not proof that a player read it. ConsentGate records that the player checked the box for that exact version.
