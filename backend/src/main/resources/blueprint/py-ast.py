# PHASE 32 (TD-57). AI Company OS's Blueprint: Python files read with Python's own
# parser. The files arrive as a JSON array on stdin ([{path, text}]); their classes,
# top-level functions, HTTP routes and imports go out as JSON on stdout. Nothing of
# the files is executed: ast.parse only parses.
import ast
import json
import sys

ROUTE_METHODS = {"get", "post", "put", "delete", "patch", "route"}


def params(node):
    out = []
    args = node.args
    for a in args.posonlyargs + args.args:
        out.append(a.arg + (": " + ast.unparse(a.annotation) if a.annotation is not None else ""))
    if args.vararg is not None:
        out.append("*" + args.vararg.arg)
    for a in args.kwonlyargs:
        out.append(a.arg + (": " + ast.unparse(a.annotation) if a.annotation is not None else ""))
    if args.kwarg is not None:
        out.append("**" + args.kwarg.arg)
    return ", ".join(out)


def facts(path, text):
    tree = ast.parse(text, filename=path)
    lines = text.splitlines()

    def line_text(node):
        return lines[node.lineno - 1].strip()[:200] if 0 < node.lineno <= len(lines) else ""

    result = {"classes": [], "functions": [], "routes": [], "prefix": "", "imports": [], "main": False}
    for node in tree.body:
        if isinstance(node, ast.ClassDef):
            result["classes"].append({"name": node.name,
                                      "bases": [ast.unparse(b) for b in node.bases]})
        elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            result["functions"].append(node.name + "(" + params(node) + ")")
            for d in node.decorator_list:
                if (isinstance(d, ast.Call) and isinstance(d.func, ast.Attribute) and d.func.attr in ROUTE_METHODS
                        and d.args and isinstance(d.args[0], ast.Constant) and isinstance(d.args[0].value, str)):
                    result["routes"].append({"method": "GET" if d.func.attr == "route" else d.func.attr.upper(),
                                             "path": d.args[0].value, "handler": node.name,
                                             "line": d.lineno, "end": node.lineno})
        elif isinstance(node, ast.Assign) and isinstance(node.value, ast.Call):
            func = node.value.func
            name = func.id if isinstance(func, ast.Name) else func.attr if isinstance(func, ast.Attribute) else ""
            if name == "APIRouter":
                for kw in node.value.keywords:
                    if kw.arg == "prefix" and isinstance(kw.value, ast.Constant) and isinstance(kw.value.value, str):
                        result["prefix"] = kw.value.value
        elif isinstance(node, ast.If):
            test = ast.unparse(node.test)
            if "__name__" in test and "__main__" in test:
                result["main"] = True
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom):
            result["imports"].append({"from": "." * node.level + (node.module or ""),
                                      "names": [a.name for a in node.names],
                                      "line": node.lineno, "text": line_text(node)})
        elif isinstance(node, ast.Import):
            result["imports"].append({"modules": [a.name for a in node.names],
                                      "line": node.lineno, "text": line_text(node)})
    return result


def main():
    sys.stdin.reconfigure(encoding="utf-8")
    sys.stdout.reconfigure(encoding="utf-8")
    files = json.load(sys.stdin)
    out = {}
    for f in files:
        try:
            out[f["path"]] = facts(f["path"], f["text"])
        except (SyntaxError, ValueError, RecursionError):
            pass  # this file is read lexically
    json.dump({"parser": "python " + sys.version.split()[0], "files": out}, sys.stdout)


if __name__ == "__main__":
    main()
