# Backup Report

- Date: 2026-10-28.
- Worktree: `E:\audit-agent-new\.runtime\budget-home-e17-20261014`.
- Branch / HEAD at start: `feat/contract-auto-delivery-20261027` / `a0a9cce974027810e44002dd1d316892ef0f0897`.
- Recovery checkpoint: `E:\audit-agent-new\.backups\unified-material-refactor-20261028`.
- The checkpoint contains `backend/`, `db/`, `frontend/`, `base-head.txt`, `initial-git-status.txt`, and `source-manifest.sha256`.
- Verification command: PowerShell SHA-256 comparison of every manifest row against the worktree.
- Observed result before the first implementation edit: 66 entries, 0 missing or mismatched files.
- The worktree already contained user changes; none were reset, cleaned, or overwritten intentionally.

This checkpoint verifies the captured tracked source files listed in the manifest. It is not a full database backup or a deployment rollback package.

## 后续恢复检查点

- 再次修改前备份：`.runtime/unified-resume-20261028-215526`，含源码、Git diff/status和SHA256.json。
- 26 新检查点：`/home/admin/audit-agent-backups/unified-material-20261028-r1`。重新捕获原80当前release与22个应用/配置文件的私有SHA清单。
- 独立QA r5 release/配置/units归档 SHA-256：`7d9bfb70e380adff46306bb6db71f9bbec2092b4e265bf296aace2045286731c3`。敏感配置仅在远端私有归档，未复制到Git。
- 本轮无DDL、业务库导出或原80切换。应用回退不等于数据库回退。
