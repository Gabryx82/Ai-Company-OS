import { useState } from "react";
import { useApi } from "../../context";
import type { HubCategory, HubSkill } from "../../api/types";
import { ProblemNote } from "../../components/ui";

/**
 * PHASE 32 (TD-76): the category of a skill is a keyword heuristic; the operator
 * corrects it here. The choice is by name, so the same skill in the library and
 * in every application follows; «Automatica» gives it back to the heuristic.
 */
export function CategoryPicker({ skill, categories, onChanged }: {
  skill: HubSkill; categories: HubCategory[]; onChanged: (message: string) => void;
}) {
  const api = useApi();
  const [value, setValue] = useState(skill.categoryChosen ? skill.category : "");
  const [problem, setProblem] = useState<unknown>(null);

  async function choose(next: string) {
    const previous = value;
    setValue(next);
    setProblem(null);
    try {
      await api.hubSetCategory(skill.name, next || null);
      const label = categories.find((c) => c.key === next)?.label;
      onChanged(label ? `${skill.name}: categoria «${label}»` : `${skill.name}: categoria automatica`);
    } catch (error) {
      setValue(previous);
      setProblem(error);
    }
  }

  return (
    <>
      <select aria-label={`Categoria di ${skill.name}`} className="hub-select" value={value} onChange={(e) => choose(e.target.value)}>
        <option value="">Automatica{skill.categoryChosen ? "" : ` (${skill.categoryLabel})`}</option>
        {categories.map((c) => <option key={c.key} value={c.key}>{c.label}</option>)}
      </select>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
    </>
  );
}
