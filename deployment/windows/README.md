# TimeLord Agent — Windows install scripts

These ship inside the release zip alongside `timelord-agent.exe` (see the
`Release` GitHub Actions workflow, `.github/workflows/release.yml`).

## Install

From an elevated PowerShell prompt, in the extracted release folder:

```powershell
.\install.ps1
```

This copies the binary to `%ProgramFiles%\TimeLord`, creates
`%ProgramData%\TimeLord` (locked down to `SYSTEM` + `Administrators` only),
and registers/starts the `TimeLordAgent` service under `LocalSystem` with
automatic restart on failure. Re-running it (e.g. after dropping in a newer
`timelord-agent.exe`) stops and replaces the existing service — it's the
current upgrade path until a proper installer package exists.

## Uninstall

```powershell
.\uninstall.ps1            # keeps session history and logs
.\uninstall.ps1 -RemoveData # also deletes %ProgramData%\TimeLord
```

## Logs

`%ProgramData%\TimeLord\logs\agent.log.<date>` (daily-rolling). Set
`RUST_LOG` as a service environment variable (e.g. via `sc.exe` or the
service's registry `Environment` value) for more verbose output; defaults to
`info`.
