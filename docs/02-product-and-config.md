# Player flow and configuration

Status: the initial schema and restricted MiniMessage formatting are implemented and used by Velocity. Paper integration, Bedrock, remote storage, configurable built-in messages, and commands remain proposed.

## Player experience

1. The plugin identifies the player and checks acceptance for the active documents.
2. Players with valid acceptance continue immediately.
3. Others see a summary with one checkbox and one Read button per required document.
4. Read opens local full text with Previous, Next, and Back buttons where needed.
5. Continue validates the required checkboxes and saves the acceptance.
6. Only a confirmed save releases the connection. Leave disconnects without granting acceptance.

Unchecked boxes stay unchecked by default. Preserve selections across Read and Back using validated session state. A document screen may offer “Accept and return” for that document. Optional informational documents require no checkbox.

Keep Leave available on every screen. Escape, client disconnect, and timeout never count as acceptance. Screen closure is not the gate: server-side connection state is the gate.

## Administrator controls

- Any document title, summary, button label, checkbox wording, and order.
- Stable document IDs and independent string versions, such as `2026-09` or `v3`.
- Full text stored locally with optional pages and restricted MiniMessage formatting.
- Required agreements. Informational documents and separate optional opt-in choices can follow later.
- Configurable language files, default language, timeouts, and unsupported-client messages.
- Commands for validation, safe reload, preview, status, document viewing, and withdrawal/reset with clear permissions.

Use exact version equality, not numeric or alphabetical ordering. A different version requires acceptance. Changing a title does not change document identity.

Save a hash and snapshot of the document text shown. Reject changed text under an already registered version and ask the admin to bump that version. This prevents silently rewriting what an older acceptance meant. Language variants belong to the same revision; record which variant was shown.

## Bedrock presentation

Use Geyser-translated dialogs as the baseline. Offer optional Cumulus forms for touch and controller layouts. Both renderers share document content, versions, session state, validation, and persistence.

The native flow uses a document menu, reading pages, and an explicit acceptance step. SimpleForm suits reading and navigation, CustomForm supplies unchecked agreement toggles, and ModalForm can provide final confirmation. Choose the exact layout after real-client tests. [Cumulus form types](https://geysermc.org/wiki/geyser/forms/)

- Convert shared formatted text to supported Bedrock text. Never send raw MiniMessage tags to forms.
- Allow Bedrock overrides for short labels and readable colors. Keep policy content shared; any content override must be versioned and recorded as a separate shown variant.
- Put essential information in visible text, without requiring hover, item tooltips, images, or external URLs.
- Keep reading and acceptance distinct.
- Handle closed, invalid, stale, and repeated responses. Closing a pending form disconnects without acceptance; never release the gate or loop forms indefinitely.
- Test button and body contrast separately, long translations, scrolling, and preserved selections.

Native forms are optional for installations. They must pass admission-stage tests before selection; installing Geyser or Floodgate alone does not establish readiness.

## Example layout

```text
plugins/ConsentGate/
  config.yml
  documents/
    community-rules.yml
    data-notice.yml
  messages/
    en-US.yml
    id-ID.yml
  data/
    consent.db
    remote-cache.db
```

The messages directory and `remote-cache.db` belong to later features. The local SQLite configuration below is runnable now.

```yaml
config-version: 1
enabled: false
scope: main
gate:
  timeout-seconds: 300
  max-pending: 128
language:
  default: en-US
  use-client-locale: true
appearance:
  title-color: gold
  accent-color: gold
  text-color: white
  muted-color: gray
  error-color: red
  button-color: white
documents:
  directory: documents
storage:
  type: sqlite
  sqlite:
    file: data/consent.db
```

This first schema accepts only relative document and database paths inside the plugin directory. `enabled: false` is the safe initial state. Enabling the gate requires at least one required document. Unsupported storage types and unknown or duplicate keys stop startup validation.

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

The optional `appearance` section supplies default colors for unformatted text and built-in dialog labels. Colors may use the sixteen named Minecraft colors or a six-digit hex value such as `#12abef`.

Document fields support named and hex colors plus `bold`, `italic`, `underlined`, `strikethrough`, and `obfuscated`. Tags must be properly closed. Interactive, hover, insertion, font, gradient, rainbow, and external-link tags are rejected during startup validation. Use YAML line breaks instead of formatting tags for new lines.

Remote settings should support host, port, database, username, password via an environment-variable reference, and verified TLS. Define that syntax during implementation. Never put passwords inside a logged JDBC URL.

## Reload and validation

Validate IDs, duplicate versions, required translations, file paths, page sizes, text formatting, and platform capability before activation. Restrict document paths to the plugin directory. Use a safe YAML parser and a limited set of MiniMessage tags; acceptance never executes configurable commands.

Parse a new configuration completely before swapping it in. A failed reload leaves the last valid configuration active. On successful document changes, pending sessions restart with the new revision and cleared selections. Reject stale callbacks. Check the active revision again before admission.

A new required document prompts players again on their next admission. Already admitted players are not kicked merely because a file was reloaded. A future explicit enforcement command can handle that separately.

Preview mode must not create acceptance records. Ship an inactive example document and require administrators to supply and enable their text before the gate can run.
