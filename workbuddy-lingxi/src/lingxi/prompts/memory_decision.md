判断本轮对话是否需要写入、更新或删除记忆，输出结构化决策。
口径：
- 用户明确要求记住或忘掉的内容必须照办（action 取 forget 或 remember，优先级最高）。
- 用户没有明说但属于稳定偏好、长期项目进展、用户稳定事实的信息也要记住。
- 会话内一次性、临时性的细节不要记。
- 以下内容禁止入记忆：{{sensitive_ban}}。
- confidence 低于 0.6 的内容不要输出。
- 与现有记忆语义重复或冲突时，用 updates 给出 memory_id 与新内容，不要重复新增。
工作目录：{{work_dir}}
用户消息：{{user_content}}
助手回复：{{assistant_content}}
现有记忆：
{{existing}}
