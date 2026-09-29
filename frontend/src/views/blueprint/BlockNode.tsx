import { memo, useContext } from "react";
import { Handle, Position, useStore, type Node, type NodeProps } from "@xyflow/react";
import type { BlueprintBlock } from "../../api/types";
import { detailFor, type Chip } from "./blueprintModel";
import { BlockActionsContext, useNodeEmphasis } from "./emphasis";
import { HTTP_COLOR, KIND_LOOK, PATH_STATE_LABEL, STATUS_LOOK } from "./blueprintStyle";

export type BlockData = { block: BlueprintBlock; kindLabel: string; chips: Chip[]; more: number };
export type BlockFlowNode = Node<BlockData, "block">;

/**
 * One component of the project, drawn as a Blueprint block (directive §2): a
 * header with kind, name, status and language; the path; the description; the
 * pseudocode preview; the relations as chips that navigate. Inputs on the left,
 * outputs on the right. How much of it shows depends on the zoom (§14): the
 * selector returns the level, not the zoom, so a block re-renders when the level
 * changes and not on every frame of a zoom.
 */
export const BlockNode = memo(function BlockNode({ data, selected }: NodeProps<BlockFlowNode>) {
  const { block, kindLabel, chips, more } = data;
  const detail = useStore((s) => detailFor(s.transform[2]));
  const emphasis = useNodeEmphasis(block.id);
  const { followChip } = useContext(BlockActionsContext);
  const look = KIND_LOOK[block.kind];
  const endpoint = block.kind === "ENDPOINT" ? block.name.match(/^(GET|POST|PUT|PATCH|DELETE|ANY)\s+(.*)$/) : null;
  const missing = block.pathState === "MISSING";
  const className = ["bp-node", selected ? "is-selected" : "", emphasis === "dim" ? "is-dim" : "",
    emphasis === "focus" ? "is-focus" : "", missing ? "is-missing" : "", block.origin === "MANUAL" ? "is-manual" : ""]
    .filter(Boolean).join(" ");

  return (
    <div className={className} style={{ ["--kind" as string]: look.color }} data-testid={`block-${block.id}`}
         aria-label={`${kindLabel} ${block.name}`}>
      <Handle type="target" position={Position.Left} className="bp-port bp-port-in" />
      <div className="bp-node-head">
        <span className="bp-mono" aria-hidden>{look.mono}</span>
        <div className="bp-node-title">
          {endpoint
            ? <strong><span className="bp-http" style={{ color: HTTP_COLOR[endpoint[1]] ?? "inherit" }}>{endpoint[1]}</span> {endpoint[2]}</strong>
            : <strong title={block.name}>{block.name}</strong>}
          <small>{kindLabel}{block.language ? ` · ${block.language}` : ""}{block.origin === "MANUAL" ? " · manuale" : ""}</small>
        </div>
        <span className={`dot ${STATUS_LOOK[block.status].dot}`} title={STATUS_LOOK[block.status].label} />
      </div>
      {detail >= 1 && (
        <div className="bp-node-body">
          {block.filePath && <div className="bp-path mono" title={block.filePath}>{block.filePath}</div>}
          {block.pathState !== "LINKED" && <div className="bp-pathstate">{PATH_STATE_LABEL[block.pathState]}</div>}
          {block.description && <p className={detail === 2 ? "bp-desc" : "bp-desc bp-clamp"}>{block.description}</p>}
          {detail === 2 && block.pseudocodePreview && <pre className="bp-pseudo">{block.pseudocodePreview}</pre>}
          {detail === 2 && chips.length > 0 && (
            <div className="bp-chips">
              {chips.map((c) => (
                <button key={c.edgeId} type="button" className={`bp-chip nodrag is-${c.origin.toLowerCase()}`}
                        title={`${c.label} → ${c.targetName}`}
                        onClick={(event) => { event.stopPropagation(); followChip(c.targetId, c.edgeId); }}>
                  <b>{c.label.toUpperCase()}</b> → {c.targetName}
                </button>
              ))}
              {more > 0 && <span className="bp-chip-more">+{more}</span>}
            </div>
          )}
          {detail === 2 && block.tags.length > 0 && (
            <div className="bp-tags">{block.tags.map((t) => <span key={t} className="chip">#{t}</span>)}</div>
          )}
        </div>
      )}
      <Handle type="source" position={Position.Right} className="bp-port bp-port-out" />
    </div>
  );
});
