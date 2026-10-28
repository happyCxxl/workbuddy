"""记忆接口：直查记忆表（不经 LLM）与物理删除。"""

from fastapi import APIRouter

from lingxi.contract import ApiError, ErrorCode, R
from lingxi.db.engine import session_scope
from lingxi.db.models import Memory
from lingxi.db.repo.memory_repo import MemoryRepo

router = APIRouter()


def _serialize(memory: Memory) -> dict[str, object]:
    """记忆序列化：ID 一律字符串。"""
    return {
        "id": str(memory.id),
        "kind": memory.kind,
        "scope": memory.scope,
        "work_dir": memory.work_dir,
        "content": memory.content,
        "confidence": memory.confidence,
        "created_at": memory.created_at.isoformat(),
    }


@router.get("/memories")
async def list_memories(scope: str | None = None, work_dir: str | None = None) -> dict[str, object]:
    """记忆列表：传 work_dir 查可见范围（全局+本目录）；不传查全部（记忆页）。"""
    async with session_scope() as s:
        repo = MemoryRepo(s)
        rows = await repo.list_for_scope(work_dir) if work_dir else await repo.list_all()
    if scope:
        rows = [m for m in rows if m.scope == scope]
    return R.ok({"memories": [_serialize(m) for m in rows]}).to_dict()


@router.delete("/memories/{memory_id}")
async def delete_memory(memory_id: int) -> dict[str, object]:
    """删除记忆：物理移除，不留副本（Never#2）。"""
    async with session_scope() as s:
        repo = MemoryRepo(s)
        deleted = await repo.delete(memory_id)
        await s.commit()
    if not deleted:
        raise ApiError(ErrorCode.NOT_FOUND, f"记忆不存在：{memory_id}")
    return R.ok(None).to_dict()
