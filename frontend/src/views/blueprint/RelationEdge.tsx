import { memo } from "react";
import { BaseEdge, EdgeLabelRenderer, getBezierPath, type Edge, type EdgeProps } from "@xyflow/react";
import type { BlueprintRelation } from "../../api/types";
import { useEdgeEmphasis } from "./emphasis";
import { ORIGIN_DASH, ORIGIN_LABEL, RELATION_COLOR } from "./blueprintStyle";

export type RelationData = { relation: BlueprintRelation; label: string };
export type RelationFlowEdge = Edge<RelationData, "relation">;

/**
 * A relation between two blocks (directive §3): a curve from an output port to
 * an input port, coloured by the family of the relation and dashed by its origin
 * -- solid when the code says it, dashed when a person drew it, dotted and moving
 * while it is only proposed. The label shows on hover, selection or emphasis; the
 * native tooltip carries the description and the place in the code.
 */
export const RelationEdge = memo(function RelationEdge(props: EdgeProps<RelationFlowEdge>) {
  const { id, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, data, selected, markerEnd } = props;
  const emphasis = useEdgeEmphasis(Number(id));
  const [path, labelX, labelY] = getBezierPath({ sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition });
  if (!data) return null;
  const { relation, label } = data;
  const color = RELATION_COLOR[relation.family];
  const strong = selected || emphasis === "focus" || emphasis === "hover";
  const code = relation.code ? `\n${relation.code.path}${relation.code.line ? `:${relation.code.line}` : ""}${relation.code.excerpt ? `\n${relation.code.excerpt}` : ""}` : "";
  const tooltip = `${label}${relation.label ? ` — ${relation.label}` : ""}\n${ORIGIN_LABEL[relation.origin]}`
    + `${relation.description ? `\n${relation.description}` : ""}${code}`;
  return (
    <>
      <BaseEdge id={id} path={path} markerEnd={markerEnd} interactionWidth={16}
                className={relation.origin === "PROPOSED" ? "bp-edge-proposed" : undefined}
                style={{ stroke: color, strokeWidth: strong ? 2.6 : 1.4, strokeDasharray: ORIGIN_DASH[relation.origin],
                  opacity: emphasis === "dim" ? 0.08 : strong ? 1 : 0.55 }} />
      <path d={path} fill="none" stroke="transparent" strokeWidth={16} className="bp-edge-hit"><title>{tooltip}</title></path>
      {strong && (
        <EdgeLabelRenderer>
          <div className="bp-edge-label nodrag nopan" style={{ transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)`,
            borderColor: color, color }}>
            {label}{relation.label ? ` · ${relation.label.length > 40 ? relation.label.slice(0, 39) + "…" : relation.label}` : ""}
          </div>
        </EdgeLabelRenderer>
      )}
    </>
  );
});
