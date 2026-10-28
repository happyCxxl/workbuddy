"""权限规则接口：规则直查与删除（裁决逻辑在权限门，不在本层）。"""

from fastapi import APIRouter

from lingxi.contract import ApiError, ErrorCode, R
from lingxi.db.engine import session_scope
from lingxi.db.repo.permission_repo import PermissionRepo

router = APIRouter()


@router.get("/permissions/rules")
async def list_rules() -> dict[str, object]:
    """规则直查。"""
    async with session_scope() as s:
        rules = await PermissionRepo(s).list_all()
    return R.ok(
        {
            "rules": [
                {
                    "id": str(r.id),
                    "tool": r.tool,
                    "scope": r.scope,
                    "action": r.action,
                    "level": r.level,
                    "work_dir": r.work_dir,
                }
                for r in rules
            ]
        }
    ).to_dict()


@router.delete("/permissions/rules/{rule_id}")
async def delete_rule(rule_id: int) -> dict[str, object]:
    """删除规则：不存在报 60002。"""
    async with session_scope() as s:
        repo = PermissionRepo(s)
        deleted = await repo.delete(rule_id)
        await s.commit()
    if not deleted:
        raise ApiError(ErrorCode.NOT_FOUND, f"规则不存在：{rule_id}")
    return R.ok(None).to_dict()
