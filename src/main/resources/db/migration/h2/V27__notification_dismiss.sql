-- Deleting a notification in the processing centre only hides it: the dedupe key stays so the same event never notifies again.
ALTER TABLE user_notification ADD COLUMN dismissed_at_ms BIGINT;
