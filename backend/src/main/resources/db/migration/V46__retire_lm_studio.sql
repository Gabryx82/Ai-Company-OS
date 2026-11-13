-- Operator review of 2026-11-12: LM Studio is not installed on the operator's machine
-- and leaves the ecosystem. PHASE 37 had put it in the software catalog (and named it
-- among the tools that receive agents, without ever writing to it: TD-100). The
-- catalog file only inserts missing keys (ADR-018 §4), so a database it already
-- reached keeps the row unless a migration takes it out.
--
-- Only an entry no agent was given goes: an agent that uses LM Studio keeps its
-- software as it was, and the operator removes it from the Software Hub if wanted.
-- Nothing is retired (catalog_retirements): the entry is no longer shipped, so there
-- is nothing a later start could put back.
DELETE FROM software
WHERE key = 'lm-studio'
  AND NOT EXISTS (SELECT 1 FROM agent_software WHERE agent_software.software_id = software.id);
