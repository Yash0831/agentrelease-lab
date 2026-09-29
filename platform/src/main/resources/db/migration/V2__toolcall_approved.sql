-- V2: the ToolCall.approved flag existed on the entity but had no column
-- (ddl-auto=validate would reject the mapping at startup).
ALTER TABLE tool_calls ADD COLUMN approved BOOLEAN NOT NULL DEFAULT FALSE;
