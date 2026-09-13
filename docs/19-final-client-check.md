# Final client check

Use a disposable installation with the candidate JAR and the [tested dependencies](05-development.md#validation-matrix). Keep normal users off the test installation. The automated runners cover storage faults and routing; this check covers what a real player sees and can operate.

## Java

Use an unmodified 1.21.6 client on Velocity and an unmodified 1.21.7 client on stock Paper 1.21.7 build 32. Also check the newer client version you intend to support.

1. Present two required documents with multiple pages and both bundled languages. Confirm the selector, text, Previous, Next, and Back work. Use a small window and a large GUI scale once.
2. Try Continue with a required box unchecked. Confirm no world entry and a readable error. Navigate away and back, then confirm checked selections are preserved.
3. Wait at least 90 seconds on the document screen with the gate timeout set above that. Accept both documents. Confirm normal world entry and movement. Reconnect and confirm no dialog appears.
4. Use an offline reset, reconnect, and choose Leave. Confirm an immediate, readable disconnect and no world entry.

## Bedrock on Paper

Use Geyser-Spigot on the same test server. Enable native forms and repeat reading, Back, partial selections, acceptance, and accepted reconnect. Close the agreement form, then close the main menu. The former should return to the menu; the latter should disconnect.

Then disable native forms, restart, reset the offline test UUID, and check the Geyser-translated dialog path. Confirm that text is readable and acceptance stays explicit. Record a failure if either path cannot deliver the form before world entry; do not allow admission as a workaround.

Report the platform build, Java or Bedrock version, Geyser build, and which step passed or failed. A screenshot is useful only if text, buttons, or the disconnect screen look wrong. These are the remaining real-client checks before declaring the candidate a release.
