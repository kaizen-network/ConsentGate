# Player flow and configuration

Status: configuration, formatting, editable messages, documents, remote storage, and administrator commands are implemented on both platforms, including login preview and document viewing. Native Bedrock presentation is shared, with real-client checks on Velocity and Paper delivery verification still pending. See [release progress](16-release-progress.md).

## Player experience

1. The plugin identifies the player and checks acceptance for the active documents.
2. Players with valid acceptance continue immediately.
3. If the optional language selector is enabled, players choose from the configured translations.
4. Others see a summary with one checkbox and one Read button per required document.
5. Read opens local full text with Previous, Next, and Back buttons where needed.
6. Continue validates the required checkboxes and saves the acceptance.
7. Only a confirmed save releases the connection. Leave disconnects without granting acceptance.

Unchecked boxes stay unchecked by default. Preserve selections across Read and Back using validated session state. A document screen may offer “Accept and return” for that document. Optional informational documents require no checkbox.

Leave is the summary exit action. Reading screens use Back and do not repeat Leave. Escape, client disconnect, and timeout never count as acceptance. Screen closure is not the gate: server-side connection state is the gate.

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

Both platforms offer optional native forms when Geyser is installed on the same proxy/server. Paper uses Geyser-Spigot and still needs Bedrock delivery verification:

```yaml
bedrock:
  native-forms: true
  button-color: dark_gray
```

The default is false, preserving translated Java dialogs. Java players and installations without a local Geyser plugin retain Java dialogs. Native mode uses Geyser's public connection API and the Cumulus supplied by Geyser; neither Geyser nor Cumulus is bundled in ConsentGate. Geyser 2.11.2 build 1235 has the configuration-stage form support reviewed for this implementation. Real Bedrock admission testing is required before production use. A separate Geyser Standalone installation is not supported by this native renderer.

The native summary is a button menu: Read buttons, Continue, and Leave. Reading pages have Previous, Next, and Back buttons. Continue opens a separate form containing agreement toggles and Bedrock's Submit button. Every required toggle must be checked. Closing the summary or language selector disconnects. Closing a reading page or acceptance form returns to the summary without accepting, so players can reread the documents. Malformed responses deny entry, and old form responses are ignored.

Native menu buttons default to dark gray for contrast. Set `bedrock.button-color` to a named Minecraft color or a quoted hex color; explicit formatting in a button label can override it. Hex colors use the nearest supported palette color. This does not change Java buttons or Bedrock's built-in Submit button. Text formatting resets between styled sections so bold titles do not make summaries or policy bodies bold. Underline and strikethrough are omitted because their Java formatting codes select colors on Bedrock.

Documents, versions, interface translations, and acceptance storage are shared with Java. Form-delivery failure does not release the backend gate or switch renderers mid-session.

Use Geyser-translated dialogs as the baseline. Offer optional Cumulus forms for touch and controller layouts. Both renderers share document content, versions, session state, validation, and persistence.

The native flow uses a document menu, reading pages, and an explicit acceptance step. SimpleForm suits reading and navigation, CustomForm supplies unchecked agreement toggles, and ModalForm can provide final confirmation. Choose the exact layout after real-client tests. [Cumulus form types](https://geysermc.org/wiki/geyser/forms/)

- Convert shared formatted text to supported Bedrock text. Never send raw MiniMessage tags to forms.
- Allow Bedrock overrides for short labels and readable colors. Keep policy content shared; any content override must be versioned and recorded as a separate shown variant.
- Put essential information in visible text, without requiring hover, item tooltips, images, or external URLs.
- Keep reading and acceptance distinct.
- Handle closed, invalid, stale, and repeated responses. Closing the main menu or language selector disconnects without acceptance. Closing reading or agreement forms returns to the menu. Never release the gate without saved acceptance.
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
    en-US.properties
    id-ID.properties
  data/
    consent.db
    remote-cache.db
```

Both platforms load editable UTF-8 interface text from `messages/en-US.properties` and `messages/id-ID.properties`. Add another `<locale>.properties` file for another interface language. Missing entries fall back to the configured default language, then English. Current files use Java properties syntax. `remote-cache.db` stores short-lived confirmed checks when optional remote storage is enabled; see [remote configuration](15-remote-storage.md).

The language selector title, prompt, labels, and order are configurable under `language.selector`. It accepts two to eight choices. Document files support up to 32 translations. A valid acceptance in any current translation skips the selector on reconnect, even when the client's language differs. Changed versions, changed accepted content, and withdrawals still require consent.

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
  selector:
    enabled: false
    title: "<gold><bold>Language / Bahasa</bold></gold>"
    prompt: "<gray>Choose the language used for these documents.</gray>"
    columns: 2
    options:
      en-US: "English"
      id-ID: "Bahasa Indonesia"
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

This first schema accepts only relative document and database paths inside the plugin directory. `enabled: false` is the safe initial state. Enabling the gate requires at least one required document. The optional language selector accepts two to eight choices in one or two columns, and every choice must have an exact translation in every required document. Unsupported storage types and unknown or duplicate keys stop startup validation.

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

Remote settings support host, port, database, username, a password environment-variable reference, and verified TLS. See the implemented syntax in [remote storage](15-remote-storage.md). Passwords are not included in logged JDBC URLs.

## Reload and validation

Validate IDs, duplicate versions, required translations, file paths, page sizes, text formatting, and platform capability before activation. Restrict document paths to the plugin directory. Use a safe YAML parser and a limited set of MiniMessage tags; acceptance never executes configurable commands.

Parse a new configuration completely before swapping it in. A failed reload leaves the last valid configuration active. Reload refuses while consent sessions or database jobs are pending. New connections use the new revision after successful reload. Reject stale callbacks and check the active revision again before admission.

A new required document prompts players again on their next admission. Already admitted players are not kicked merely because a file was reloaded. A future explicit enforcement command can handle that separately.

Preview mode uses one test connection and disconnects without creating acceptance records. See [administrator commands](07-admin-commands.md). Bundled example documents are inactive; administrators must supply and enable their text before the gate can run.
