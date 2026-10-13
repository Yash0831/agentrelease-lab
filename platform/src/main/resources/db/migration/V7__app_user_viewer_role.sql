-- OIDC-authenticated users may carry the VIEWER role (read-only). The
-- original CHECK constraint on app_users.role predates OIDC sign-in and
-- just-in-time provisioning of OIDC subjects as app_users rows.
ALTER TABLE app_users DROP CONSTRAINT IF EXISTS app_users_role_check;
ALTER TABLE app_users ADD CONSTRAINT app_users_role_check
    CHECK (role IN ('ADMIN','APPROVER','AGENT','REQUESTER','VIEWER'));
