"""健康检查：进程存活探测，走统一信封。"""

from fastapi import APIRouter

from lingxi.contract import R

router = APIRouter()


@router.get("/health")
async def health() -> dict[str, object]:
    """返回存活状态：不检查业务依赖。"""
    return R.ok("ok").to_dict()
