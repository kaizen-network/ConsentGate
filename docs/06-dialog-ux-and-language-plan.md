# Dialog UX and language

Status: Java presentation and the shared native Bedrock renderer are implemented on both platforms. Paper Bedrock runtime verification and broader Geyser compatibility testing remain open.

Bedrock testing confirmed acceptance and summary closure through Geyser's translated forms. A summary containing checkboxes becomes a CustomForm with an action dropdown and Submit. An optional native Bedrock flow now provides separate menu buttons and an acceptance form with toggles. Cumulus response tests pass, and real-client testing confirmed the native menu, acceptance, formatting, and return from the agreement form. Closing the native agreement form returns to the document menu; closing the main menu disconnects.

Closing a summary executes Leave; closing a multi-page reader executes Back. Dialog actions close the screen immediately. Real Java client testing confirmed that Leave displays the disconnect message promptly without a waiting overlay or manual Back click. Rapid invalid submissions schedule a validation redisplay instead of leaving the screen closed.

## Layout

The summary uses two action columns, with document Read buttons followed by Continue. Leave is a separate exit action. Missing checkboxes show a translated validation message.

Reader screens do not repeat Leave. One-page documents show only Back. Multi-page documents put Previous and Next in the two-column action grid when available, with Back as the separate exit action. Back preserves checkbox selections.

The bundled Terms and Privacy templates each have two pages in English and Indonesian. Their version is `draft-2`. Page structure participates in the document hash, so pagination changes require a new version when earlier content has been recorded.

Summary and reader columns are currently fixed at two. Only the language selector supports configuring one or two columns.

## Disconnect handling

Denial removes admission permission, releases the held initial-server event, clears the dialog, and requests disconnect. Backend connections remain denied. The wire test requires a clear-dialog packet before the disconnect packet. Real-client checks remain necessary to verify that no waiting screen persists.

## Languages

Automatic client-locale selection remains the default. An optional selector appears only when fresh consent is needed. Administrators configure its title, prompt, option labels, order, and columns under `language.selector`.

An enabled selector requires two to eight options, with an exact translation in every required document for every option. Invalid formatting and duplicate normalized locale tags reject startup. A single-option selector is not supported.

Selection uses a connection token and a one-time claim to reject stale and repeated choices. The selected locale triggers another acceptance check before the summary or admission.

A valid acceptance of any current translation allows reconnecting without the selector, even when the client reports another language. Changed versions, changed accepted content, and withdrawals still require consent. No persistent language preference is stored.

Interface text comes from editable UTF-8 `messages/<locale>.properties` files. English and Indonesian defaults are bundled. Missing entries fall back to the configured default language, then English.

## Remaining validation

- Exercise selector actions, including stale tokens and repeated clicks, in the wire probe.
- Extend real Java client checks for translated navigation and rapid validation errors.
- Validate native Bedrock forms before backend admission, including scrolling, closure, preserved selections, reconnects, and storage failures.
- Verify the shared Bedrock flow through Paper's configuration connection.
