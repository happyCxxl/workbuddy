"""厂商密钥入库：api_key 替代环境变量名。"""

import sqlalchemy as sa
from alembic import op

revision = "0011"
down_revision = "0010"
branch_labels = None
depends_on = None


def upgrade() -> None:
    """新增 api_key 列，删除 api_key_env。"""
    op.add_column("model_vendor", sa.Column("api_key", sa.Text(), nullable=True))
    op.drop_column("model_vendor", "api_key_env")


def downgrade() -> None:
    """回退：恢复环境变量名列，删除 api_key。"""
    op.add_column("model_vendor", sa.Column("api_key_env", sa.String(length=128), nullable=True))
    op.drop_column("model_vendor", "api_key")
