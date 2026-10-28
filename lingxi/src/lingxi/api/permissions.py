"""权限接口：预设档位切换与规则直查。"""

from fastapi import APIRouter
from pydantic import BaseModel

from lingxi.contract import ApiError, ErrorCode, R
from lingxi.core.log import EventType, append_event
from lingxi.db.engine import session_scope
from lingxi.db.repo.permission_repo import PermissionRepo
from lingxi.domain.permission.gate import PermissionGate

router = APIRouter()


class PresetRequest(BaseModel):
    """档位切换请求。"""

    preset: str
    session_id: int | None = None


def make_router(gate: PermissionGate) -> APIRouter:
    """构造权限路由：闭包持有权限门。"""

    @router.put("/permissions/preset")
    async def set_preset(request: PresetRequest) -> dict[str, object]:
        """切换预设档位；携带会话时落 permission/policy 审计事件。"""
        gate.set_preset(request.preset)
        if request.session_id is not None:
            await append_event(request.session_id, EventType.PERMISSION_POLICY, {"preset": request.preset})
        return R.ok({"preset": gate.preset}).to_dict()

    @router.get("/permissions/rules")
    async def list_rules() -> dict[str, object]:
        """规则直查：设置页展示。"""
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

    return router
