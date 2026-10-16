-- A pre-signup role belongs to one explicit roster identity, never to public signup input.
-- Existing linked accounts retain their current global_role and all existing team assignments.
ALTER TABLE roster_entries
    ADD COLUMN signup_global_role VARCHAR(20) NOT NULL DEFAULT 'MEMBER'
        CHECK (signup_global_role IN ('MEMBER', 'GLOBAL_ADMIN'));
