-- Personal rule coefficient (docs/design/2026-10-09-搭配审美…设计.md 第三十二节, D75): which avoid-table rules fired on
-- each shown outfit and how many points they took, so a later like or dislike can lean the user towards or away from
-- that rule. Only rule ids and points; no garment data beyond what the row already holds.
ALTER TABLE recommendation_log ADD COLUMN rule_hits_json CLOB;
