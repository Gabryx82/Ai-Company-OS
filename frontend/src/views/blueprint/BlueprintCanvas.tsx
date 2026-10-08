import { useCallback, useEffect, useMemo } from "react";
import {
  Background, BackgroundVariant, Controls, MarkerType, MiniMap, ReactFlow, useEdgesState, useNodesState,
  type Connection, type NodeTypes, type EdgeTypes, type OnSelectionChangeParams,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import type { BlueprintBlock, BlueprintGraph, BlueprintRelation, BlueprintVocabulary } from "../../api/types";
import { BlockNode, type BlockFlowNode } from "./BlockNode";
import { RelationEdge, type RelationFlowEdge } from "./RelationEdge";
import { chipsBySource } from "./blueprintModel";
import { KIND_LOOK, RELATION_COLOR } from "./blueprintStyle";
import type { EmphasisStore } from "./emphasis";

const nodeTypes: NodeTypes = { block: BlockNode };
const edgeTypes: EdgeTypes = { relation: RelationEdge };

/**
 * The Blueprint's canvas (directive §1): pan, zoom, drag, box and shift
 * selection, ports that connect, a minimap, a fine grid. It owns only what React
 * Flow needs to move things under the pointer; what is shown comes from the view.
 */
export function BlueprintCanvas({ graph, blocks, relations, vocabulary, editMode, emphasis, onSelect, onConnectRequest,
  onMoved, onPaneClick, onMoveEnd }: {
  graph: BlueprintGraph;
  blocks: BlueprintBlock[];
  relations: BlueprintRelation[];
  vocabulary: BlueprintVocabulary;
  editMode: boolean;
  emphasis: EmphasisStore;
  onSelect: (selection: { nodes: number[]; edge: number | null }) => void;
  onConnectRequest: (source: number, target: number) => void;
  onMoved: (positions: Record<string, { x: number; y: number }>) => void;
  onPaneClick: () => void;
  /** PHASE 32 (TD-61): the view settled after a pan or a zoom. */
  onMoveEnd?: () => void;
}) {
  const kindLabel = useMemo(() => new Map(vocabulary.kinds.map((k) => [k.kind, k.label])), [vocabulary]);
  const relationLabel = useMemo(() => new Map(vocabulary.relations.map((r) => [r.relation, r.label])), [vocabulary]);
  const chips = useMemo(() => chipsBySource(graph, vocabulary.relations), [graph, vocabulary]);

  const [nodes, setNodes, onNodesChange] = useNodesState<BlockFlowNode>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<RelationFlowEdge>([]);

  // Blocks: positions from the server's layout, selection kept across rebuilds.
  useEffect(() => {
    setNodes((previous) => {
      const selected = new Set(previous.filter((n) => n.selected).map((n) => n.id));
      return blocks.map((b) => {
        const p = graph.layout.positions[String(b.id)] ?? { x: 0, y: 0 };
        const c = chips.get(b.id);
        return {
          id: String(b.id), type: "block" as const, position: { x: p.x, y: p.y }, selected: selected.has(String(b.id)),
          data: { block: b, kindLabel: kindLabel.get(b.kind) ?? b.kind, chips: c?.chips ?? [], more: c?.more ?? 0 },
        };
      });
    });
  }, [blocks, graph.layout, chips, kindLabel, setNodes]);

  useEffect(() => {
    setEdges(relations.map((r) => ({
      id: String(r.id), source: String(r.source), target: String(r.target), type: "relation" as const,
      markerEnd: { type: MarkerType.ArrowClosed, color: RELATION_COLOR[r.family], width: 16, height: 16 },
      data: { relation: r, label: relationLabel.get(r.relation) ?? r.relation },
    })));
  }, [relations, relationLabel, setEdges]);

  const onSelectionChange = useCallback(({ nodes: n, edges: e }: OnSelectionChangeParams) => {
    onSelect({ nodes: n.map((x) => Number(x.id)), edge: n.length === 0 && e.length === 1 ? Number(e[0].id) : null });
  }, [onSelect]);

  const onConnect = useCallback((c: Connection) => {
    if (c.source && c.target && c.source !== c.target) onConnectRequest(Number(c.source), Number(c.target));
  }, [onConnectRequest]);

  const onNodeDragStop = useCallback((_: unknown, __: unknown, dragged: BlockFlowNode[]) => {
    const moved: Record<string, { x: number; y: number }> = {};
    dragged.forEach((n) => { moved[n.id] = { x: Math.round(n.position.x), y: Math.round(n.position.y) }; });
    onMoved(moved);
  }, [onMoved]);

  return (
    <ReactFlow<BlockFlowNode, RelationFlowEdge>
      nodes={nodes} edges={edges} nodeTypes={nodeTypes} edgeTypes={edgeTypes}
      onNodesChange={onNodesChange} onEdgesChange={onEdgesChange} onSelectionChange={onSelectionChange}
      onConnect={onConnect} onNodeDragStop={onNodeDragStop} onPaneClick={onPaneClick} onMoveEnd={() => onMoveEnd?.()}
      onEdgeMouseEnter={(_, e) => emphasis.hover(Number(e.id))} onEdgeMouseLeave={() => emphasis.hover(null)}
      nodesConnectable={editMode} edgesFocusable elementsSelectable deleteKeyCode={null}
      selectionKeyCode="Shift" multiSelectionKeyCode={["Meta", "Control"]}
      onlyRenderVisibleElements minZoom={0.08} maxZoom={2} fitView fitViewOptions={{ padding: 0.15 }}
      proOptions={{ hideAttribution: true }} colorMode="dark" aria-label="Blueprint del progetto">
      <Background variant={BackgroundVariant.Lines} gap={28} lineWidth={0.5} color="rgba(139,152,184,0.07)" />
      <Controls showInteractive={false} position="bottom-right" />
      {/* A stroke in the block's own colour: on a large Blueprint a block is under a pixel tall in the minimap. */}
      <MiniMap pannable zoomable position="top-right" className="bp-minimap"
               nodeColor={(n) => KIND_LOOK[(n as BlockFlowNode).data.block.kind].color}
               nodeStrokeColor={(n) => KIND_LOOK[(n as BlockFlowNode).data.block.kind].color}
               nodeStrokeWidth={Math.max(8, Math.sqrt(graph.nodes.length) * 5)} maskColor="rgba(7,12,24,0.6)" />
    </ReactFlow>
  );
}
