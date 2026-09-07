-- V16 — Remove the AI coding tool: the feature was decided against and its
-- permission is dropped from the app. The enum is persisted as a string
-- (User.permissions, EAGER), so any already-granted 'AI_CODING_TOOL' rows
-- would break user loading (Enum.valueOf) once the constant is gone —
-- delete them here. Grants nothing, touches nothing else.
-- (The historical V12 comment naming ai-coding-tool is left untouched:
-- applied migrations are immutable.)
DELETE FROM cafe_user_permission WHERE permission = 'AI_CODING_TOOL';
