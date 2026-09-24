-- PHASE 10, measured (2026-09-24): a local 9B planner writes about 6 tokens/s on the
-- operator's machine, so a plan is generated in stages -- the phases, then the
-- tasks of each phase -- and the run says which stage it is in. Additive.

ALTER TABLE plan_runs ADD COLUMN progress VARCHAR(200);
