---
title: Player experience
description: What players see on Java and Bedrock, and how each button and close action behaves.
order: 5
---

# Player experience

## Java

![Required agreements with Read and Continue buttons](images/required-agreements.png)

1. If the language selector is on, the player chooses a language first.
2. The summary shows each required document with a checkbox and a Read button, then Continue and Leave.
3. Read opens the full text. Longer documents have Previous and Next; Back returns to the summary. Checkboxes keep their state while reading.
4. Continue checks that every box is ticked. If one is missing, a warning appears and the player stays on the screen.
5. After a successful save, the player continues to the server.

![Warning when an agreement is not checked](images/missing-agreement.png)

Checkboxes always start unchecked. Leave disconnects right away without saving anything. Closing the summary (for example with Escape) counts as Leave, and closing a reading page counts as Back. Disconnecting or timing out never counts as acceptance.

## Bedrock

Bedrock players see one of two versions:

- **Native forms** (`bedrock.native-forms: true`): a button menu with Read, Continue, and Leave. Continue opens a separate form with one toggle per document and Bedrock's Submit button. Every toggle must be on.
- **Translated dialogs** (default): Geyser converts the Java dialog. The summary becomes a form with an action dropdown and Submit.

With native forms:

- Reading pages have Previous, Next, and Back buttons.
- Closing a reading page or the agreement form returns to the menu, so players can reread.
- Closing the menu or the language selector disconnects.
- Broken or outdated form responses are rejected, never accepted.

Native forms need Geyser on the same proxy or server (Geyser-Spigot on Paper). ConsentGate uses the Cumulus library that comes with Geyser and does not bundle it. If a form cannot be delivered, the player is not let in.

Documents, versions, and acceptance records are shared between Java and Bedrock.

## Timeouts and failures

- The player has `gate.timeout-seconds` to accept. After that, they are disconnected.
- If the database cannot save the acceptance, the player is not let in and sees a message to try again.
- On Velocity, the player does not connect to any backend server until the acceptance is saved.
- On Paper, the player does not enter the world until the acceptance is saved.
