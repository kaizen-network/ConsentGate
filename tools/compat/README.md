# Historical compatibility patch

`grim-63a684d-configuration-timeout.patch` is a tested temporary patch for Grim revision `63a684d`. It is not part of the ConsentGate runtime and is not an official Grim release.

Grim already has a corresponding upstream fix: [commit `29d8fb4`](https://github.com/GrimAnticheat/Grim/commit/29d8fb4fd0ca6c9d69b7a2a4f6a733669d3be0e3), associated with the behavior reported in [issue #2844](https://github.com/GrimAnticheat/Grim/issues/2844). Prefer testing an upstream build containing that change. Do not submit this historical patch as a new fix or apply it over current code without review.

See the [investigation and test results](../../docs/12-paper-anticheat-compatibility.md) for artifact hashes, reproduction, test limits, and the plan to retire the workaround. The upstream build has not yet passed our runtime checks. Keep the old patch only for reproducing the recorded comparison.
