"""技能目录接口：当前会话可用的 /skill-name 列表。"""

from fastapi import APIRouter

from lingxi.app.auth import CurrentUserDep
from lingxi.contract import R
from lingxi.domain.session.service import get_session
from lingxi.domain.skills.service import list_skills


def make_router() -> APIRouter:
    """构建技能路由。"""
    router = APIRouter()

    @router.get("/sessions/{session_id}/skills")
    async def list_session_skills(session_id: int, user: CurrentUserDep) -> dict[str, object]:
        """列出当前工作区可调用的技能。"""
        session = await get_session(session_id, user.id)
        return R.ok(
            {
                "skills": [
                    {
                        "name": skill.name,
                        "description": skill.description,
                        "args_hint": skill.args_hint,
                        "source": skill.source,
                    }
                    for skill in list_skills(session.work_dir)
                ]
            }
        ).to_dict()

    return router
