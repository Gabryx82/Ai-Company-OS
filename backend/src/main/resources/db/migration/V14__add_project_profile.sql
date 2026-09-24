-- PHASE 9 (ADR-020 §2.1). The profile of a project: what kind of software it is,
-- the proposed stack, where its files live, how much the operator delegates,
-- and where its plan stands.
--
-- Additive. The two NOT NULL columns carry a default so that existing rows are
-- valid without a backfill that would assert anything: GUIDED is the directive's
-- starting level of Human-in-the-Loop (ADR-022), NONE is the truth -- no project
-- created before this migration has a plan. project_type stays NULL for them: no
-- value is the correct one for a project nobody classified.

ALTER TABLE projects ADD COLUMN project_type   VARCHAR(24);
ALTER TABLE projects ADD COLUMN stack          VARCHAR(2000);
ALTER TABLE projects ADD COLUMN workspace_path VARCHAR(1000);
ALTER TABLE projects ADD COLUMN autonomy_level VARCHAR(24) NOT NULL DEFAULT 'GUIDED';
ALTER TABLE projects ADD COLUMN plan_status    VARCHAR(24) NOT NULL DEFAULT 'NONE';

ALTER TABLE projects ADD CONSTRAINT projects_project_type_check CHECK (project_type IS NULL OR project_type IN (
    'WEB_APP', 'BACKEND', 'MOBILE', 'DESKTOP', 'AI_ML', 'GAME', 'THREE_D', 'DATA', 'AUTOMATION', 'API',
    'FULL_STACK', 'OTHER'));
ALTER TABLE projects ADD CONSTRAINT projects_autonomy_level_check
    CHECK (autonomy_level IN ('GUIDED', 'SUPERVISED', 'DELEGATED', 'FINAL_REVIEW'));
ALTER TABLE projects ADD CONSTRAINT projects_plan_status_check
    CHECK (plan_status IN ('NONE', 'DRAFT', 'APPROVED'));
