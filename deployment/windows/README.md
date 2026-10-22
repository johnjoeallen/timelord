# TimeLord Agent — Windows install scripts

These ship inside the release zip alongside `timelord-agent.exe` and
`timelord-notifier.exe` (see the
`Release` GitHub Actions workflow, `.github/workflows/release.yml`).

## Install

Extract the release zip, then open PowerShell **as Administrator** in the extracted folder and run:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\install.ps1
```

The first line is usually needed: Windows blocks scripts from a downloaded zip by default
("running scripts is disabled on this system", or a security warning). `-Scope Process` lifts that
for this PowerShell window only, so nothing is changed permanently and it reverts when you close
the window. If you would rather not change the policy even for the session, run
`Unblock-File .\install.ps1, .\uninstall.ps1` first, or right-click the zip → Properties → Unblock
before extracting it. `uninstall.ps1` needs the same treatment.

This copies the binary to `%ProgramFiles%\TimeLord`, creates
`%ProgramData%\TimeLord` (locked down to `SYSTEM` + `Administrators` only),
and registers/starts the `TimeLordAgent` service under `LocalSystem` with
automatic restart on failure. It also installs `timelord-notifier.exe` and a
`TimeLord Notifier` scheduled task that starts it at every user's logon (see
[Notifier](#notifier)); pass `-SkipNotifier` to install only the service.
Re-running it (e.g. after dropping in a newer
`timelord-agent.exe`) stops and replaces the existing service, so it also
serves as the upgrade path — a packaged MSI/EXE installer may replace this
script later, but this is the real install mechanism today, not a stopgap.

## Notifier

`timelord-notifier.exe` runs in each logged-on user's session (started by the
`TimeLord Notifier` scheduled task, as the user with limited rights). It asks
the agent service, over the local pipe `\\.\pipe\timelord-notify`, what is
about to happen, and shows Windows toasts: warnings before the device sleeps or
shuts down (at 15, 5, 1 minute and 30 seconds) and any message an administrator
sends from the dashboard. It has no window of its own.

* Log: `%LOCALAPPDATA%\TimeLord\notifier.log` (per user, rotated at 256 KiB).
* Which messages it has already shown: `%LOCALAPPDATA%\TimeLord\notifier-shown.json`.
* To stop it temporarily: `Stop-Process -Name timelord-notifier` (it returns at
  the next logon). The service enforces the schedule whether or not it runs; the
  notifier only gives the user notice.

## Uninstall

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass   # if this is a new window
.\uninstall.ps1            # keeps session history and logs
.\uninstall.ps1 -RemoveData # also deletes %ProgramData%\TimeLord
```

Uninstalling also removes the `TimeLord Notifier` task. Per-user files under
`%LOCALAPPDATA%\TimeLord` are left in place.

## Logs

`%ProgramData%\TimeLord\logs\agent.log.<date>` (daily-rolling). Set
`RUST_LOG` as a service environment variable (e.g. via `sc.exe` or the
service's registry `Environment` value) for more verbose output; defaults to
`info`.

## Alternative: the agent's own install command

`timelord-agent.exe` can also install/uninstall itself directly (uses the
Service Control Manager API, not `sc.exe`):

```powershell
timelord-agent.exe install
timelord-agent.exe status
timelord-agent.exe uninstall
```

This doesn't set up the `%ProgramData%\TimeLord` ACLs `install.ps1` does, so
prefer the script for a real deployment; the CLI command is mainly useful
for quick local testing. See `agent/README.md`.
