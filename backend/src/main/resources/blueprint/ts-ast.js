// PHASE 32 (TD-57). AI Company OS's Blueprint: TypeScript and JavaScript files read
// with TypeScript's own parser. The files arrive as a JSON array on stdin
// ([{path, text}]); what each declares, exports, imports and calls goes out as JSON
// on stdout. Nothing of the files is executed: ts.createSourceFile only parses.
"use strict";
const ts = require(process.env.AICOS_TS_MODULE);

const API = /^\/api\//;
const METHODS = /^(GET|POST|PUT|DELETE|PATCH)$/;

function scriptKind(path) {
  if (/\.tsx$/i.test(path)) return ts.ScriptKind.TSX;
  if (/\.jsx$/i.test(path)) return ts.ScriptKind.JSX;
  if (/\.(js|mjs|cjs)$/i.test(path)) return ts.ScriptKind.JS;
  return ts.ScriptKind.TS;
}

function exported(node) {
  return (ts.getCombinedModifierFlags(node) & ts.ModifierFlags.Export) !== 0;
}

function hasJsx(node) {
  let found = false;
  const walk = (n) => {
    if (found) return;
    if (ts.isJsxElement(n) || ts.isJsxSelfClosingElement(n) || ts.isJsxFragment(n)) { found = true; return; }
    ts.forEachChild(n, walk);
  };
  walk(node);
  return found;
}

function routeOf(node) {
  if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) {
    return API.test(node.text) ? node.text.split("?")[0] : null;
  }
  if (ts.isTemplateExpression(node) && API.test(node.head.text)) {
    let route = node.head.text;
    for (const span of node.templateSpans) route += "{}" + span.literal.text;
    return route.split("?")[0];
  }
  return null;
}

function facts(path, text) {
  const sf = ts.createSourceFile(path, text, ts.ScriptTarget.Latest, true, scriptKind(path));
  const lines = text.split(/\r?\n/);
  const line = (n) => sf.getLineAndCharacterOfPosition(n.getStart(sf)).line + 1;
  const lineText = (n) => (lines[line(n) - 1] || "").trim().slice(0, 200);
  const r = { exports: [], functions: [], classes: [], types: [], components: [], hooksDefined: [], hooksUsed: [],
    imports: [], api: [], props: {} };
  const props = (name, params) => {
    if (params.length > 0 && ts.isObjectBindingPattern(params[0].name)) {
      r.props[name] = params[0].name.elements.map((e) => e.name.getText(sf)).filter((p) => /^[A-Za-z_$][\w$]*$/.test(p));
    }
  };
  for (const st of sf.statements) {
    if (ts.isFunctionDeclaration(st) && st.name) {
      const name = st.name.text;
      if (exported(st)) { r.exports.push(name); r.functions.push(name); }
      if (/^[A-Z]/.test(name) && hasJsx(st)) { r.components.push(name); props(name, st.parameters); }
    } else if (ts.isClassDeclaration(st) && st.name) {
      if (exported(st)) {
        const ext = (st.heritageClauses || []).find((h) => h.token === ts.SyntaxKind.ExtendsKeyword);
        r.exports.push(st.name.text);
        r.classes.push({ name: st.name.text, extends: ext ? ext.types[0].expression.getText(sf) : null });
      }
    } else if ((ts.isInterfaceDeclaration(st) || ts.isTypeAliasDeclaration(st) || ts.isEnumDeclaration(st)) && exported(st)) {
      r.exports.push(st.name.text);
      r.types.push(st.name.text);
    } else if (ts.isVariableStatement(st)) {
      for (const d of st.declarationList.declarations) {
        if (!ts.isIdentifier(d.name)) continue;
        const name = d.name.text;
        if (exported(st)) r.exports.push(name);
        let init = d.initializer;
        if (init && ts.isCallExpression(init) && init.arguments.length > 0 && /^(React\.)?(memo|forwardRef)$/.test(init.expression.getText(sf))) {
          init = init.arguments[0];
        }
        if (init && (ts.isArrowFunction(init) || ts.isFunctionExpression(init)) && /^[A-Z]/.test(name) && hasJsx(init)) {
          r.components.push(name);
          props(name, init.parameters);
        }
      }
    }
  }
  r.hooksDefined = r.exports.filter((e) => /^use[A-Z]/.test(e));
  const walk = (n) => {
    if (ts.isImportDeclaration(n) && ts.isStringLiteral(n.moduleSpecifier)) {
      const clause = n.importClause;
      r.imports.push({ spec: n.moduleSpecifier.text, what: clause ? clause.getText(sf).replace(/^type\s+/, "").replace(/\s+/g, " ") : "",
        line: line(n), text: n.getText(sf).replace(/\s+/g, " ").slice(0, 200), typeOnly: !!(clause && clause.isTypeOnly) });
    } else if (ts.isExportDeclaration(n) && n.moduleSpecifier && ts.isStringLiteral(n.moduleSpecifier)) {
      r.imports.push({ spec: n.moduleSpecifier.text, what: "", line: line(n), text: n.getText(sf).replace(/\s+/g, " ").slice(0, 200),
        typeOnly: !!n.isTypeOnly });
    } else if (ts.isCallExpression(n)) {
      const callee = n.expression;
      const dynamic = callee.kind === ts.SyntaxKind.ImportKeyword || (ts.isIdentifier(callee) && callee.text === "require");
      if (dynamic && n.arguments.length > 0 && ts.isStringLiteral(n.arguments[0])) {
        r.imports.push({ spec: n.arguments[0].text, what: "", line: line(n), text: lineText(n), typeOnly: false });
      }
      if (ts.isIdentifier(callee) && /^use[A-Z]/.test(callee.text) && !r.hooksDefined.includes(callee.text)
          && !r.hooksUsed.includes(callee.text)) {
        r.hooksUsed.push(callee.text);
      }
    }
    const route = routeOf(n);
    if (route) {
      let method = null;
      if (n.parent && ts.isCallExpression(n.parent)) {
        for (const a of n.parent.arguments) {
          if (a === n) break;
          if (ts.isStringLiteral(a) && METHODS.test(a.text)) method = a.text;
        }
      }
      r.api.push({ method, route, line: line(n), text: lineText(n) });
    }
    ts.forEachChild(n, walk);
  };
  walk(sf);
  return r;
}

let input = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", (d) => { input += d; });
process.stdin.on("end", () => {
  const files = JSON.parse(input);
  const out = {};
  for (const f of files) {
    try {
      out[f.path] = facts(f.path, f.text);
    } catch (e) {
      // this file is read lexically
    }
  }
  process.stdout.write(JSON.stringify({ parser: "typescript " + ts.version, files: out }));
});
