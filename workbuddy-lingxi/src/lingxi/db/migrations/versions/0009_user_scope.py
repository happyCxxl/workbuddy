"""多用户隔离：五张表加 user_id（历史数据归引导用户），并重建唯一键。"""

import sqlalchemy as sa
from alembic import op

revision = "0009"
down_revision = "0008"
branch_labels = None
depends_on = None

LEGACY_USER_ID = "1"

TABLES = ("workspace", "ai_session", "sub_session", "memory", "persona_version")


def upgrade() -> None:
    """加列并回填：历史数据归引导用户，列保留默认值以兼容旧写入路径。"""
    for table in TABLES:
        op.add_column(
            table,
            sa.Column("user_id", sa.BigInteger(), nullable=False, server_default=LEGACY_USER_ID),
        )
        op.create_index(f"ix_{table}_user_id", table, ["user_id"])
    op.drop_constraint("uq_workspace_path", "workspace", type_="unique")
    op.create_unique_constraint("uq_workspace_user_path", "workspace", ["user_id", "path"])
    op.drop_constraint("persona_version_version_key", "persona_version", type_="unique")
    op.create_unique_constraint("uq_persona_user_version", "persona_version", ["user_id", "version"])
    op.create_index("ix_ai_session_user_updated", "ai_session", ["user_id", "updated_at"])


def downgrade() -> None:
    """回退：删索引与唯一键，恢复全局唯一口径，再删列。"""
    op.drop_index("ix_ai_session_user_updated", table_name="ai_session")
    op.drop_constraint("uq_persona_user_version", "persona_version", type_="unique")
    op.create_unique_constraint("persona_version_version_key", "persona_version", ["version"])
    op.drop_constraint("uq_workspace_user_path", "workspace", type_="unique")
    op.create_unique_constraint("uq_workspace_path", "workspace", ["path"])
    for table in TABLES:
        op.drop_index(f"ix_{table}_user_id", table_name=table)
        op.drop_column(table, "user_id")
