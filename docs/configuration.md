---
title: Configuration
description: config.yml settings, scopes, languages, setting limits, and how reload works.
order: 3
---

# Configuration

## Plugin folder

```text
plugins/ConsentGate/          (plugins/consentgate/ on Velocity)
  config.yml
  documents/
    terms.yml
    privacy.yml
  messages/
    en-US.properties
    id-ID.properties
  data/
    consent.db
    remote-cache.db           (only with MySQL/MariaDB)
```

The generated `config.yml` explains each setting in its comments. You can also read the [default config](https://github.com/kaizen-network/ConsentGate/blob/main/presentation/src/main/resources/config.yml). Updates keep your values and comments, and add new settings to your file with their default values. Comment changes on settings you already have are not applied. If a file cannot be updated safely, for example when a section is written on one line, the file is left unchanged, the server log shows a warning, and the plugin starts normally. Copy the new settings in by hand in that case. To stop updates to a file, make it read-only.

## Main settings

| Setting | What it does |
| --- | --- |
| `enabled` | Turns the gate on. Set it to `true` after adding your documents, then run `consentgate reload`. |
| `scope` | The consent group for this whole proxy or server. See [scopes](#scopes-and-multiple-servers). |
| `gate.timeout-seconds` | How long a player has to accept. Timing out disconnects them. |
| `gate.max-pending` | How many players can wait on the consent screen at once. Extra connections are rejected. |
| `language.*` | Default language, client language matching, and the optional language selector. See [languages](#languages). |
| `appearance.*` | Default colors for the Java consent screen. |
| `bedrock.native-forms` | Use native Bedrock forms when Geyser is installed on the same proxy or server. |
| `bedrock.button-color` | Color of native Bedrock menu buttons. |
| `documents.directory` | Folder with your document files, inside the plugin folder. |
| `storage.*` | SQLite, MySQL, or MariaDB, plus the local cache for remote storage. See [storage](storage.md). |

## Scopes and multiple servers

`scope` names a consent group for the whole plugin instance. It does not select a Velocity backend.

| Goal | Setup |
| --- | --- |
| Share consent across proxies | Same `scope` on each proxy, the same MySQL/MariaDB database, matching document IDs, versions, and text, and the same player UUIDs (through proxy forwarding) |
| Separate network and server consent | `scope: network` on Velocity and `scope: survival` on the Survival Paper server. Players can be asked once at each gate, even with a shared database. |

The database does not copy document files between servers, so keep them in sync yourself. Separate SQLite files never share consent. Changing a scope does not move old records: acceptance only counts in the scope where it was given.

## Languages

The default language is `en-US`, and matching the client's language is on by default. Missing translations fall back to the default language, then English.

The optional language selector (`language.selector`) lets players pick a language before the documents. It appears only when a player needs to accept. It needs two to eight choices, and every choice must have a translation in every required document. You can set its title, prompt, labels, order, and one or two columns.

A player who accepted any current translation of the documents is not asked again, even if their client language changes. New versions and withdrawals still ask again.

Interface text (buttons, errors, disconnect messages) lives in `messages/<locale>.properties`, using Java properties syntax in UTF-8. English and Indonesian are included. Add another file to support another language. Disconnect messages for errors, such as a busy gate, a timeout, or a storage failure, use the `error-*` keys.

On start, new keys are added to `en-US.properties` and `id-ID.properties`. Files you add for other languages are not changed: keys missing from them use the default language, then English.

## Colors

`appearance` sets default colors for unformatted text and built-in labels on the Java screen. `bedrock.button-color` sets the native Bedrock menu button color (default `dark_gray` for contrast). Colors can be one of the sixteen named Minecraft colors or a hex value like `"#12abef"`. On Bedrock, hex colors use the nearest supported color. Formatting inside a document can override these defaults.

## Setting limits

| Setting | Allowed values |
| --- | --- |
| `scope` | 1 to 64 characters: lowercase letters, numbers, `_`, `-`, `.`; starts with a letter or number |
| `gate.timeout-seconds` | 30 to 1,800 seconds |
| `gate.max-pending` | 1 to 10,000 players |
| `language.selector.options` | 2 to 8 choices |
| `language.selector.columns` | 1 or 2 |
| Remote connect and socket timeouts | 100 to 30,000 milliseconds each |
| `storage.cache.freshness-seconds` | 0 to 300; 0 checks the database on every join |
| `storage.cache.max-entries` | 1 to 1,000,000 |
| Locale tags | Up to 64 characters |

Paths must be relative and stay inside the plugin folder. Unknown or duplicate keys fail validation. See [writing documents](documents.md#limits) for document limits.

## Reload and validation

After editing files, run `consentgate validate`, then `consentgate reload` in the console (add `/` in game).

- **Reload can apply:** documents, messages, languages, colors, Bedrock presentation, the timeout, and enabling a disabled gate.
- **Restart needed for:** disabling an active gate, changing its scope or storage/cache settings, changing `gate.max-pending`, and replacing the JAR.
- Reload refuses while players are on the consent screen or database work is running. Try again after they finish.
- A failed reload keeps the previous settings running. A failed startup must be fixed and restarted.
- Reload does not kick players who are already online. Changed documents apply on each player's next connection.

See [commands](commands.md#validate-and-reload) for details.
