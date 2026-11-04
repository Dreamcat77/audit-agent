# Local preflight — 2026-08-14

## Repository gate

- Repository: `D:\code\audit_agent_1D`
- Required branch: `feature/bootstrap-backend-baseline`
- Actual branch: `feature/bootstrap-backend-baseline`
- Initial working tree: clean
- Initial HEAD: `394fb0a`
- Checkpoint at HEAD: `pre-codex-20260814`
- Git initialization, branch switching, commits, tags, pushes, hard resets, and cleans were not performed.

The initial commands were:

```powershell
git status
git branch --show-current
git --no-pager log --oneline --decorate --max-count=5
```

## Local tool preflight

- PowerShell: `5.1.19041.6456`
- Java runtime: `openjdk 17.0.19` (Temurin)
- Java compiler: `javac 17.0.19`
- `JAVA_HOME`: `C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\`
- Global `mvn`: not installed; this is expected and is handled by the repository Maven Wrapper.
- Port 8080: supplied as initially available; runtime use is verified separately in delivery evidence.

## Network constraint

dev29 connectivity: **DEFERRED - VPN unavailable**

This preflight did not attempt any dev29 or customer-environment connectivity.
