# UI Requirements

Nothing in `ui/` currently references AKASHA. `BackendResolver` computes and publishes
`HealthState` every 10 seconds and no one consumes it. `AkashaManager.ask()` is ready to call
and nothing calls it. This is the largest missing piece.

Stack: Jetpack Compose, Material 3, MVVM with `StateFlow`. Follow the existing conventions in
`ui/` and `core/ui/component/`.

## Design principle

The user is frightened, possibly injured, possibly holding a cracked phone in poor light with
one hand. Every decision follows from that:

- Answer first, chrome second.
- Large tap targets, high contrast, no thin fonts.
- Never hide the fact that information is unverified or that the source was a peer.
- Never show a spinner without a cancel path.

## 1. Connectivity tier badge

Persistent, small, always visible in the AKASHA surface. Consumes
`AkashaManager.backendResolver.healthState`.

| Tier | Label | Colour intent | Sub-label |
|---|---|---|---|
| `T4_FULL` | Full | success | "Ground Station + N peers" |
| `T3_WEAK` | Weak | success-muted | "Ground Station only" |
| `T2_TRICKLE` | Trickle | warning | "via gateway peer" |
| `T1_MESH` | Mesh | warning | "N peers nearby" |
| `T0_ALONE` | Alone | neutral | "Offline pack only" |

Requirements:

- **Never colour a tier red.** `T0_ALONE` is a fully supported operating mode, not an error.
  Red would tell a user the app is broken when it is working exactly as designed.
- Tapping it opens a diagnostics sheet (section 6).
- Include `semantics { contentDescription = ... }` with the full sentence, since colour alone
  must not carry the meaning.

## 2. Ask screen

Single text field plus a prominent Ask action.

States:

- **Idle** — field, plus 4–6 quick-tap chips for the highest-frequency emergencies:
  Bleeding, CPR, Burns, Fracture, Snake bite, Flood. These matter because typing is hard
  under stress.
- **Working** — show which backend is being tried and allow cancelling. The mesh path can
  take up to 8 s; a silent spinner for 8 s reads as a hang.
  > "Searching offline pack… asking nearby phones…"
- **Answered** — results list (section 3).
- **Refused** — refusal card (section 4).

## 3. Result card

One card per passage or facility. Every card must show:

| Element | Rule |
|---|---|
| Text | Verbatim from the pack. **Never truncate mid-sentence** with an ellipsis; expand instead. |
| Source | `sourceDoc`, always visible, not behind a tap |
| Pack version | `packVersion`, small but present |
| Backend | Which tier answered: "from your phone" / "from Ground Station" / "from a nearby phone" |
| Confidence | See below |

Confidence presentation:

- `HIGH` → no banner. A clean card is the signal.
- `LOW` → amber inline note: "Closest match I have — please confirm with a responder if you
  can."
- Never render the raw cosine score in the main UI. `0.5090` is meaningless to a user and
  invites false precision. Put it in the diagnostics sheet.

## 4. Refusal card

This is a first-class outcome and must look deliberate, not like a failure.

```
No verified match

I don't have verified guidance for that. I won't guess.

In an emergency, call 112.                    [ Call 112 ]

Closest thing I found (may not be relevant):
  <nearest result, clearly de-emphasised, still with its source>
```

Requirements:

- The "Call 112" action must be a real `ACTION_DIAL` intent (dial, not call — let the user
  confirm).
- The nearest-result section must be visually subordinate and explicitly labelled as possibly
  irrelevant. `QueryHandler` already returns it for exactly this purpose.
- Do not use error iconography or red. This is a correct answer.

## 5. Facility (POI) results

Distinct from passage results.

Per facility:

- Name, and `name_local` when present (Bengali matters in Kolkata)
- Category and matched specialty as chips
- **"approx N.N km"** — never travel time, never a route. The app has no road data and roads
  may be flooded.
- Operator badge: Government / Private / NGO. A user needs this; a private hospital may
  demand payment.
- 24h badge when `emergency_24h`
- Phone → `ACTION_DIAL` when present. **When `phone` is null, show nothing** rather than a
  disabled button, and never substitute a generic number as if it were the facility's.
- **Unverified banner** whenever `data_status != "verified"`:
  > "Location from offline pack, not individually verified."
- "Open in maps" via `geo:` URI — but only when a maps app is present, and it must degrade
  to showing raw coordinates offline.

Sort strictly by distance ascending. Do not reorder by semantic score; the user asked for
*nearest*.

## 6. Diagnostics sheet

For field debugging and for the demo. Reachable by tapping the tier badge.

Show: tier and active backend; Ground Station URL and reachability; `recall_ok` from
`/health`; peer count; whether this device is advertising as a gateway; embedder model name
and `semanticSearchReady`; `HIGH`/`LOW` thresholds; pack version and entry count; last query's
raw scores.

Include a **"Set Ground Station address"** field wired to
`AkashaManager.setGroundStationUrl()`. mDNS discovery exists but will not work on every
network, and without a manual override a failed discovery is unrecoverable in the field.

## 7. Gateway indicator

When this device is itself acting as a gateway (Ground Station reachable, so the announce
flag is set), show a small persistent indicator. Two reasons: the operator should know they
are serving others, and it is the only way to confirm the gateway role on real hardware
without adb.

## Accessibility

- Every interactive element gets a `contentDescription`.
- Minimum touch target 48 dp.
- Meet WCAG AA contrast (4.5:1 body, 3:1 large). Note that full WCAG conformance needs manual
  testing with a screen reader and expert review — automated checks are not sufficient.
- Support system font scaling up to 200% without clipping. Test at 200%; the result cards
  will be the first thing to break.
- Do not encode meaning in colour alone — pair every colour with text or an icon.
- Announce state changes (working → answered → refused) via live region semantics so a
  screen-reader user is not left waiting silently.

## Deliverables

1. `ui/akasha/AkashaViewModel.kt` — exposes `healthState`, query state, calls
   `AkashaManager.ask()`.
2. `ui/akasha/AkashaScreen.kt` — ask + results.
3. `ui/akasha/component/TierBadge.kt`
4. `ui/akasha/component/ResultCard.kt`
5. `ui/akasha/component/RefusalCard.kt`
6. `ui/akasha/component/FacilityCard.kt`
7. `ui/akasha/AkashaDiagnosticsSheet.kt`
8. Navigation entry from the main screen.
9. Compose UI tests for: refusal rendering, LOW-confidence hedge, unverified facility banner,
   200% font scale. These are behavioural requirements, not cosmetics — test them.
