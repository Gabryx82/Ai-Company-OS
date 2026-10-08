-- PHASE 32 (TD-76). The functional category of a skill is a keyword heuristic
-- (SkillCategories); this is the operator's correction of it. By name, lower case:
-- the same skill in the library and in every application is one choice. No row,
-- the heuristic decides.
--
-- Additive: a new table.
CREATE TABLE skill_categories (
    skill_name  VARCHAR(200) PRIMARY KEY,
    category    VARCHAR(40)  NOT NULL,
    set_by      VARCHAR(120) NOT NULL,
    set_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
