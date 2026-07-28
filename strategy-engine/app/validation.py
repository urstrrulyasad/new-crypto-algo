"""AST-based validation gate for AI-generated strategy code.

Strategies may only import pandas / numpy / ta and must not perform any I/O,
network access, exec/eval, filesystem or OS interaction. The strategy contract
mirrors freqtrade's IStrategy: a class exposing populate_indicators,
populate_entry_trend and populate_exit_trend plus stoploss / minimal_roi /
timeframe attributes.
"""
from __future__ import annotations

import ast

ALLOWED_IMPORTS = {"pandas", "numpy", "ta", "math", "typing", "dataclasses"}
FORBIDDEN_CALLS = {"eval", "exec", "compile", "open", "__import__", "input", "breakpoint",
                   "exit", "quit", "globals", "locals", "vars", "setattr", "delattr"}
FORBIDDEN_ATTRS = {"__globals__", "__builtins__", "__subclasses__", "__bases__", "__mro__",
                   "__code__", "__closure__"}
REQUIRED_METHODS = {"populate_indicators", "populate_entry_trend", "populate_exit_trend"}


def validate_strategy_code(source: str) -> dict:
    errors: list[str] = []
    try:
        tree = ast.parse(source)
    except SyntaxError as e:
        return {"valid": False, "errors": [f"Syntax error: {e}"]}

    class_found = False
    methods_found: set[str] = set()

    for node in ast.walk(tree):
        if isinstance(node, (ast.Import, ast.ImportFrom)):
            module = node.module.split(".")[0] if isinstance(node, ast.ImportFrom) and node.module else None
            names = [module] if module else [a.name.split(".")[0] for a in node.names]
            for name in names:
                if name not in ALLOWED_IMPORTS:
                    errors.append(f"Forbidden import: {name}")
        elif isinstance(node, ast.Call):
            fn = node.func
            if isinstance(fn, ast.Name) and fn.id in FORBIDDEN_CALLS:
                errors.append(f"Forbidden call: {fn.id}")
        elif isinstance(node, ast.Attribute):
            if node.attr in FORBIDDEN_ATTRS:
                errors.append(f"Forbidden attribute access: {node.attr}")
        elif isinstance(node, ast.ClassDef):
            class_found = True
            for item in node.body:
                if isinstance(item, ast.FunctionDef):
                    methods_found.add(item.name)

    if not class_found:
        errors.append("No strategy class found")
    missing = REQUIRED_METHODS - methods_found
    if missing:
        errors.append(f"Missing required methods: {sorted(missing)}")

    return {"valid": not errors, "errors": errors}


def load_strategy_class(source: str):
    """Load a validated strategy in a restricted namespace and return the class."""
    import pandas, numpy, ta, math  # noqa: F401

    def guarded_import(name, globals=None, locals=None, fromlist=(), level=0):  # noqa: A002
        if name.split(".")[0] not in ALLOWED_IMPORTS:
            raise ImportError(f"Import of '{name}' is not allowed in strategies")
        return __import__(name, globals, locals, fromlist, level)

    safe_builtins = {
        n: __builtins__[n] if isinstance(__builtins__, dict) else getattr(__builtins__, n)
        for n in ("abs", "min", "max", "sum", "len", "range", "enumerate", "zip", "map",
                  "filter", "sorted", "round", "float", "int", "str", "bool", "list",
                  "dict", "set", "tuple", "isinstance", "getattr", "hasattr", "print",
                  "ValueError", "TypeError", "Exception", "__build_class__", "__name__",
                  "staticmethod", "classmethod", "property", "super", "object")
    }
    safe_builtins["__import__"] = guarded_import

    namespace: dict = {
        "__builtins__": safe_builtins,
        "pandas": pandas, "pd": pandas,
        "numpy": numpy, "np": numpy,
        "ta": ta, "math": math,
    }
    exec(compile(source, "<strategy>", "exec"), namespace)  # noqa: S102 - gated by AST validation
    for value in namespace.values():
        if isinstance(value, type) and hasattr(value, "populate_indicators"):
            return value
    raise ValueError("No strategy class found after load")
