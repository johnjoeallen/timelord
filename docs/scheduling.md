# Calendars and scheduling

A **calendar** tells a device when it must be off. You create named calendars in the dashboard,
assign each device to one, and the agent puts the device to sleep (or shuts it down) at the
calendar's off times. "On" simply means *allowed to be on again* — nothing wakes a sleeping
device.

!!! note "Developer preview"
    Scheduling is new. The agent-side sleep and shutdown have not been exercised on real hardware
    beyond compile checks and unit tests — try it on a spare machine first (see
    [Trying it safely](#trying-it-safely)).

## How a calendar works

A calendar has a **name**, a **timezone**, and an **action** taken at off time:

| Action     | What the agent does |
|------------|---------------------|
| `SLEEP`    | Suspends the device (`SetSuspendState`, never hibernate) and keeps re-suspending it until the on time if someone wakes it. |
| `SHUTDOWN` | Runs `shutdown.exe /s /t 30` — a 30-second on-screen warning, then power off. |

### Weekly rules (the base schedule)

Each rule has a set of **days**, an **off** time and an **on** time. A rule belongs to the
*evening it starts on*, which makes overnight rules read the way people say them:

| Rule | Off | On | Meaning |
|------|-----|----|---------|
| Sun–Thu | 22:30 | 06:30 | Off at 22:30 that evening, allowed again at 06:30 next morning |
| Fri, Sat | 01:00 | 06:30 | The night of Friday is off from 01:00 **Saturday** to 06:30 Saturday |

If the off time is earlier than the on time (01:00 vs 06:30) it falls after midnight, on the next
calendar day. A day may appear in at most one rule, and a rule's off and on times must differ. A
day with no rule has no off window at all.

### Overrides (holidays and one-offs)

An **override** is a date range with its own off and on times. For every night that *starts* within
the range it replaces the weekly rule; the weekly rules themselves are never changed, so removing
the override puts everything back. A single day is an override whose start and end date are the
same (in the dashboard, leave "To" blank).

If overrides overlap, the **narrowest range wins**; on a tie the most recently created wins.

!!! example
    The base calendar above, plus an override *20 Dec – 5 Jan, off 01:00, on 06:30*: every night
    in that range goes off at 01:00 instead of 22:30. A second override for *24 Dec only, off
    23:30* wins on that one night because it is narrower.

Use the **Next 14 days** table on a calendar's page (or `GET /api/v1/calendars/{id}/windows`) to
see exactly what a calendar resolves to before relying on it.

### Allowing a device longer

On a device's page, **+15 min / +30 min / +1 hour** grant a temporary **extension**:

- If the device is already inside an off window, it is allowed back on for that long from now.
- Otherwise the next off window starts that much later.
- Each request may grant 1–240 minutes; the page shows "Extended until HH:MM" with a **Clear**
  button.

An extension never edits the calendar. It is cleared if you assign the device a different
calendar.

## How the device enforces it

The controller resolves the calendar (rules, overrides and any extension) into **concrete off
windows** — absolute start and end times for the next 7 days — and sends them to the agent with a
**revision** that changes whenever they do. The agent therefore never has to understand rules or
overrides.

- **Cached locally.** The latest schedule is saved as `schedule.json` next to `agent.toml`, so
  enforcement continues across restarts and while the controller is unreachable.
- **Changes arrive immediately.** Besides every heartbeat, the agent holds a long poll open to
  `GET /api/v1/agents/{id}/schedule?revision=…`. The controller answers the moment an extension,
  assignment or calendar edit changes the revision, so "+30 minutes" takes effect within moments,
  not at the next heartbeat. With no change the request simply returns after about 30 seconds and
  the agent asks again.
- **Enforcement loop.** The agent acts when the current time is inside an off window, and
  re-checks at the next window start, on any schedule change, and at least every 60 seconds — so a
  device woken mid-window is put back to sleep within about a minute. Each action is reported as
  `POWER_ACTION_REQUESTED` / `_COMPLETED` / `_FAILED` events.

### Devices without a calendar

A device with no calendar assigned keeps the older hard-wired behaviour, which is **not
configurable from the controller**:

- an unconditional sleep between **02:00 and 06:00** local time, checked every 5 minutes; and
- the [offline fallback](agent.md#overnight-sleep-and-offline-fallback) (sleep between 01:00 and 08:30 while the
  controller is unreachable).

Both stop applying as soon as the agent has a cached calendar schedule.

## Admin API

All of these are unauthenticated in this preview — see the [security boundary](architecture.md#security-boundary).

| Method and path | Purpose |
|-----------------|---------|
| `GET /api/v1/calendars` · `GET /api/v1/calendars/{id}` | List / read calendars (with rules and overrides) |
| `POST /api/v1/calendars` | Create |
| `PUT /api/v1/calendars/{id}` | Replace name, timezone, action and the **whole set of weekly rules** (overrides untouched) |
| `DELETE /api/v1/calendars/{id}` | Delete; devices using it lose their schedule |
| `POST /api/v1/calendars/{id}/overrides` | Add an override |
| `DELETE /api/v1/calendars/{id}/overrides/{overrideId}` | Remove an override |
| `GET /api/v1/calendars/{id}/windows?from=&days=` | Preview the resolved off windows |
| `PUT /api/v1/devices/{id}/calendar` | Assign (`{"calendarId": "<uuid>"}`) or clear (`{"calendarId": null}`) |
| `POST /api/v1/devices/{id}/extend` | Allow longer (`{"minutes": 30}`) |
| `GET /api/v1/agents/{id}/schedule` | Agent long poll for schedule changes (`204` = no calendar) |

=== "Create a calendar"

    ```console
    $ curl -X POST http://localhost:9099/api/v1/calendars \
        -H 'Content-Type: application/json' -d '{
          "name": "School kids",
          "timezone": "Europe/Dublin",
          "action": "SLEEP",
          "rules": [
            {"days": ["SUNDAY","MONDAY","TUESDAY","WEDNESDAY","THURSDAY"], "offTime": "22:30", "onTime": "06:30"},
            {"days": ["FRIDAY","SATURDAY"], "offTime": "01:00", "onTime": "06:30"}
          ]
        }'
    ```

=== "Add a holiday override"

    ```console
    $ curl -X POST http://localhost:9099/api/v1/calendars/$CALENDAR_ID/overrides \
        -H 'Content-Type: application/json' -d '{
          "name": "Christmas",
          "startDate": "2026-12-20",
          "endDate": "2027-01-05",
          "offTime": "01:00",
          "onTime": "06:30"
        }'
    ```

=== "Another 30 minutes"

    ```console
    $ curl -X POST http://localhost:9099/api/v1/devices/$DEVICE_ID/extend \
        -H 'Content-Type: application/json' -d '{"minutes": 30}'
    ```

Validation errors come back in the usual [error shape](controller.md#api), for example
`INVALID_TIMEZONE`, `INVALID_RULE` (a day in two rules, or equal off/on times),
`INVALID_OVERRIDE`, `CALENDAR_NAME_TAKEN` (`409`), and `NO_CALENDAR` / `NO_OFF_WINDOW` (`409`) when
extending a device that has nothing to extend.

## Trying it safely

1. Create a calendar with an off time about 20 minutes ahead and the **Sleep** action, and assign
   it to a spare device.
2. Watch the device sleep at the off time; wake it and confirm it is put back to sleep.
3. Assign it again, press **+15 min** shortly before the off time, and confirm it stays on.
4. Only then try **Shutdown**, again on a machine you can afford to power off.
