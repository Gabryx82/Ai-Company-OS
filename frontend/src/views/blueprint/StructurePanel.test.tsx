import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import type { CodeStructure } from "../../api/types";
import { shortMethod, StructurePanel } from "./StructurePanel";

const STRUCTURE = {
  packageName: "com.acme", className: "Orders", extendsType: null, implementsTypes: [], annotations: [], fields: [],
  methods: [{ name: "place", signature: "void place(String o)", visibility: "public", returnType: "void", parameters: ["String o"],
    annotations: [], exceptions: [], calls: ["store.save()"], callTargets: [], isStatic: false, startLine: 4, endLine: 4,
    pseudocode: null, invokes: ["com.acme.Store#save", "com.acme.Orders#audit"] }],
  dependencies: [], exceptions: [], relatedTests: [], signature: null, inputs: [], output: null, visibility: null,
  httpMethod: null, route: null, controller: null, requestDto: null, responseDto: null, service: null, authorization: null,
  statusCodes: [], calls: [], calledBy: [], table: null, columns: [], exports: [], imports: [], props: [], hooks: [],
} as unknown as CodeStructure;

/** PHASE 32 (TD-58): each method says which methods of the project it invokes. */
describe("StructurePanel — the methods a method invokes", () => {
  it("lists them by type and name, with the whole name in the title", () => {
    render(<StructurePanel structure={STRUCTURE} section="CLASS" editable={false} onChange={vi.fn()} />);
    const invokes = screen.getByLabelText("Metodi invocati da place");
    expect(invokes.textContent).toContain("Store.save()");
    expect(invokes.textContent).toContain("Orders.audit()");
    expect(screen.getByTitle("com.acme.Store#save")).toBeTruthy();
  });

  it("shortens a method name to its type and method", () => {
    expect(shortMethod("com.acme.shop.Customer#notify")).toBe("Customer.notify()");
  });
});
