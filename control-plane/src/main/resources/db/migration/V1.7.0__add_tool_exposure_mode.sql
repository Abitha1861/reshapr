-- Flyway migration V1.7.0
-- Add the per-ConfigurationPlan tool exposure mode driving how the gateway advertises tools:
--   TOOLS  (default) one MCP tool per exposed operation,
--   CODE   only the Code Mode meta-tools (search_tools, get_api_types, execute_code),
--   HYBRID both.
-- NULL means TOOLS, preserving the behavior of every existing plan.

ALTER TABLE configuration_plans
    ADD COLUMN IF NOT EXISTS tool_exposure_mode VARCHAR(16);
