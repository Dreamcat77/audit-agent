# 采购、预算、合同角色

三个角色分别为“采购”（PROCUREMENT_USER）、“预算”（BUDGET_USER）、“合同”（CONTRACT_USER）。系统管理员（SYSTEM_ADMIN）可以查看全部页面和全部数据；采购、预算、合同角色分别只能进入自己的业务模块，并且只能查看本人上传的材料及其派生结果。同角色的不同账号也不能互看。服务端 API 会同步拒绝跨模块访问，文件下载、报告、历史结果和直接访问资源链接执行同样限制。给已有账号追加个人角色后，即使保留旧角色，也执行对应模块和个人材料限制；SYSTEM_ADMIN 优先，含该角色时按管理员权限处理。

角色和账号关系直接在 DM8 的 `SYS_ROLE`、`SYS_USER_ROLE` 中维护，不新增管理员授权页面或快捷授权接口。账号关系调整后重新登录生效。不迁移或清除历史材料，不改写历史上传者。

部署需执行 `db/dm8/V058__personal_upload_roles.sql` 插入三条角色参考数据。脚本可重复执行，无 DDL；不需要新增表、恢复记录或历史数据清理。

为已有账号追加角色时，使用数据库客户端绑定实际登录账号和角色编码执行以下 SQL；重复执行不增加重复关系：

```sql
INSERT INTO SYS_USER_ROLE (USER_ID, ROLE_ID)
SELECT U.ID, R.ID FROM SYS_USER U CROSS JOIN SYS_ROLE R
WHERE U.USERNAME = :username AND U.STATUS = 'ACTIVE'
  AND R.ROLE_CODE = :roleCode AND R.STATUS = 'ACTIVE'
  AND R.ROLE_CODE IN ('PROCUREMENT_USER', 'BUDGET_USER', 'CONTRACT_USER')
  AND NOT EXISTS (SELECT 1 FROM SYS_USER_ROLE UR
                  WHERE UR.USER_ID = U.ID AND UR.ROLE_ID = R.ID);
```

SYSTEM_ADMIN 保留当前全部页面。采购账号可使用采购审查及采购相关页面（审批任务、投标评审、黑名单），预算账号只显示预算审查，合同账号显示合同审核及其项目管理入口。模块账号不显示跨模块工作台，登录后直接进入所属模块。通知、认证和健康检查等公共接口仍可用，但通知打开的目标若超出当前模块，将回到所属模块。其他不带个人角色的旧角色保持原有访问行为；此规则不改变其既有权限配置。
