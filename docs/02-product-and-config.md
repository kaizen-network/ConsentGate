# Player flow and configuration

Configuration, editable messages, documents, storage, and administrator commands are available on Velocity and Paper. Native Bedrock forms have recorded client checks on both platforms; see the [tested setups and results](19-final-client-check.md).

## Player experience

1. The plugin identifies the player and checks acceptance for the active documents.
2. Players with valid acceptance continue immediately.
3. If the optional language selector is enabled, players choose from the configured translations.
4. Others see a summary with one checkbox and one Read button per required document.
5. Read opens local full text with Previous, Next, and Back buttons where needed.
6. Continue validates the required checkboxes and saves the acceptance.
7. Only a confirmed save releases the connection. Leave disconnects without granting acceptance.

Checkboxes start unchecked and keep their state while reading and returning to the summary. Only documents with `required: true` appear in the consent flow. `required: false` hides a document from that flow; it does not create an optional checkbox or an informational page. Such files are still loaded and validated.

Leave is the summary exit action. Reading screens use Back and do not repeat Leave. Escape, client disconnect, and timeout never count as acceptance. Screen closure is not the gate: server-side connection state is the gate.

## Administrator controls

- Any document title, summary, button label, checkbox wording, and order.
- Stable document IDs and independent string versions, such as `2026-09` or `v3`.
- Full text stored locally with optional pages and restricted MiniMessage formatting.
- Required agreements. Optional opt-in choices and informational-only login documents are not supported.
- Configurable language files, default language, timeouts, and unsupported-client messages.
- Commands for validation, safe reload, preview, status, document viewing, and withdrawal/reset with clear permissions.

Keep `id` stable: it identifies the document, independently of its filename or title. Versions are quoted strings, such as `"v2"`, compared exactly rather than numerically. Use a new version when changing an active or previously saved translation, including its title, summary, checkbox label, Read label, page titles, page text, or formatting inside those fields. Even a spelling or color-tag edit counts. Changing global `appearance` colors or document order does not change the stored document text.

A new required version requests consent on the next connection. Reload rejects edits under the same active version even before anyone has accepted it. Saved text is also checked against its original version. Update shared installations together so the same scope, document ID, version, and locale never refer to different text.

## Scopes and multiple servers

`scope` is a consent group for one whole plugin instance. It is not a backend routing rule. For shared consent, use the same MySQL/MariaDB database and scope, matching document IDs, versions and text, and consistent player UUIDs through proxy forwarding. Document files must be kept in sync separately. Independent SQLite files do not share consent.

For separate consent, use `scope: network` on Velocity and `scope: survival` on the Survival Paper server. A player may then accept once on the proxy and again on that server. Changing a scope does not move or delete old records; existing acceptance only counts in its original scope. Restart to change the scope of an active gate.

## Bedrock presentation

Both platforms offer optional native forms when Geyser is installed on the same proxy/server. Paper uses Geyser-Spigot. See the [client results](19-final-client-check.md) for the tested setups:

```yaml
bedrock:
  native-forms: true
  button-color: dark_gray
```

The default is false, preserving translated Java dialogs. Java players and installations without a local Geyser plugin retain Java dialogs. Native mode uses Geyser's public connection API and the Cumulus supplied by Geyser; neither Geyser nor Cumulus is bundled in ConsentGate. Geyser 2.11.2 build 1235 has the configuration-stage form support reviewed for this implementation. Real Bedrock admission testing is required before production use. A separate Geyser Standalone installation is not supported by this native renderer.

The native summary is a button menu: Read buttons, Continue, and Leave. Reading pages have Previous, Next, and Back buttons. Continue opens a separate form containing agreement toggles and Bedrock's Submit button. Every required toggle must be checked. Closing the summary or language selector disconnects. Closing a reading page or acceptance form returns to the summary without accepting, so players can reread the documents. Malformed responses deny entry, and old form responses are ignored.

Native menu buttons default to dark gray for contrast. Set `bedrock.button-color` to a named Minecraft color or a quoted hex color; explicit formatting in a button label can override it. Hex colors use the nearest supported palette color. This does not change Java buttons or Bedrock's built-in Submit button. Text formatting resets between styled sections so bold titles do not make summaries or policy bodies bold. Underline and strikethrough are omitted because their Java formatting codes select colors on Bedrock.

Documents, versions, interface translations, and acceptance storage are shared with Java. Form-delivery failure does not release the backend gate or switch renderers mid-session.

Test reading, acceptance, closing forms, and reconnect with your own Geyser and authentication setup. Native and translated forms use the same documents and consent records.

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

Locale tags have a shared 64-character limit across configuration, documents, messages, and storage. Both platforms reject missing or visibly blank required interface messages during enabled startup, validation, and reload.

The language selector title, prompt, labels, and order are configurable under `language.selector`. It accepts two to eight choices. Document files support up to 32 translations. A valid acceptance in any current translation skips the selector on reconnect, even when the client's language differs. Changed versions, changed accepted content, and withdrawals still require consent.

## Setting limits

Use the generated `config.yml`, or compare with the [current default config](../presentation/src/main/resources/config.yml). Existing files are kept on updates; new settings and comments must be copied in manually.

| Setting | Accepted values |
| --- | --- |
| `scope` | 1?64 characters: lowercase letters, numbers, `_`, `-`, `.`; start with a letter or number |
| `gate.timeout-seconds` | 30?1,800 seconds; timeout disconnects the player |
| `gate.max-pending` | 1?10,000 waiting players; extra connections are rejected |
| `language.selector.options` / `columns` | 2?8 choices / 1?2 columns; enabled choices need exact translations in every required document |
| Remote connection/socket timeouts | 100?30,000 milliseconds each |
| `storage.cache.freshness-seconds` | 0?300; use 0 to check the database on every admission |
| `storage.cache.max-entries` | 1?1,000,000 |
| Document `id` | 1?64 lowercase letters, numbers, `_`, `-`; start with a letter or number |
| Document `version` | A quoted string of 1?64 characters |
| Document files | Up to 32 `.yml`/`.yaml` files directly in the documents folder; subfolders and `.example` files are not loaded |
| Per document | Up to 32 translations, 32 pages per translation, and a 256 KiB file |

Paths must be relative and stay inside the plugin folder. Unknown or duplicate YAML keys fail validation. Enabling requires at least one document with `required: true`. Deactivating all documents cannot leave an enabled gate running.

## Document example

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

Enter the database password literally in `storage.remote.password`. Environment-variable references are not expanded. Keep the config and its backups private. See [remote storage setup](15-remote-storage.md) for connection settings, automatic table creation, and certificates.

## Reload and validation

After editing, run `consentgate validate`, then `consentgate reload` in the console. In game, use a leading `/` and the appropriate [command permission](07-admin-commands.md). Validation checks document IDs, translations, paths, page sizes, formatting, and the supported reload rules.

A disabled gate can be enabled by setting `enabled: true` and reloading. Validation while disabled does not open storage; reload opens it when enabling. A failed reload keeps the previous state. Disabling an active gate, changing its scope or storage/cache settings, or changing `gate.max-pending` requires a restart. A failed startup must also be fixed and restarted.

Reload refuses while consent sessions or database jobs are pending. Retry after they finish. New connections use the new revision after a successful reload.

A new required document prompts players again on their next admission. Already admitted players are not kicked merely because a file was reloaded. A future explicit enforcement command can handle that separately.

Preview mode uses one test connection and disconnects without creating acceptance records. See [administrator commands](07-admin-commands.md). Bundled example documents are inactive; administrators must supply and enable their text before the gate can run.
