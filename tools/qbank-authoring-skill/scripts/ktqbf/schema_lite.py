"""KT-QBF 校验用的最小 JSON Schema draft-07 子集校验器。

为什么自己写：qbank-authoring Skill 会被用户丢进任意环境（可能没有 jsonschema 包、
没有网络）。校验器必须零第三方依赖就能跑，否则"生成即校验"的承诺就不成立。

支持的关键字（恰好覆盖随附 5 个 schema 用到的部分）：
  type / enum / const / pattern / minLength / maxLength
  minimum / maximum / exclusiveMinimum / exclusiveMaximum
  minItems / maxItems / uniqueItems / items
  required / properties / additionalProperties / allOf / anyOf / oneOf / not
  $ref（仅限本文档内 "#/definitions/xxx" 与 "#/properties/..."）/ if-then-else
"""

from __future__ import annotations

import json
import re
from typing import Any

_JSON_TYPES = {
    "object": dict,
    "array": list,
    "string": str,
    "integer": int,
    "number": (int, float),
    "boolean": bool,
    "null": type(None),
}


class SchemaError(Exception):
    pass


def _type_ok(value: Any, expected: str) -> bool:
    if expected == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if expected == "number":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    if expected == "boolean":
        return isinstance(value, bool)
    py = _JSON_TYPES.get(expected)
    if py is None:
        raise SchemaError(f"schema 使用了不支持的 type: {expected}")
    return isinstance(value, py)


def _equal(a: Any, b: Any) -> bool:
    if isinstance(a, bool) or isinstance(b, bool):
        return a is b
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return a == b
    if type(a) is not type(b):
        return False
    return a == b


def _resolve_ref(root: dict, ref: str) -> dict:
    if not ref.startswith("#/"):
        raise SchemaError(f"仅支持文档内 $ref，收到 {ref}")
    node: Any = root
    for part in ref[2:].split("/"):
        part = part.replace("~1", "/").replace("~0", "~")
        if not isinstance(node, dict) or part not in node:
            raise SchemaError(f"无法解析 $ref: {ref}")
        node = node[part]
    return node


def _pointer(path: str) -> str:
    return path or "$"


def validate(instance: Any, schema: dict, *, root: dict | None = None, path: str = "") -> list[str]:
    """返回错误列表；空列表表示通过。"""
    root = root if root is not None else schema
    errs: list[str] = []
    where = _pointer(path)

    if "$ref" in schema:
        target = _resolve_ref(root, schema["$ref"])
        errs += validate(instance, target, root=root, path=path)
        # $ref 与同级关键字共存在 draft-07 中不合法，这里只按 ref 处理
        return errs

    # --- 组合 ---
    if "allOf" in schema:
        for i, sub in enumerate(schema["allOf"]):
            errs += validate(instance, sub, root=root, path=path)
    if "anyOf" in schema:
        branches = [validate(instance, sub, root=root, path=path) for sub in schema["anyOf"]]
        if all(b for b in branches):
            errs.append(f"{where}: 不满足 anyOf 中任何一个分支")
    if "oneOf" in schema:
        branches = [validate(instance, sub, root=root, path=path) for sub in schema["oneOf"]]
        if sum(1 for b in branches if not b) != 1:
            errs.append(f"{where}: 必须恰好满足 oneOf 中的一个分支")
    if "not" in schema:
        if not validate(instance, schema["not"], root=root, path=path):
            errs.append(f"{where}: 命中了 not 排除的结构")
    if "if" in schema:
        cond = validate(instance, schema["if"], root=root, path=path)
        if not cond and "then" in schema:
            errs += validate(instance, schema["then"], root=root, path=path)
        elif cond and "else" in schema:
            errs += validate(instance, schema["else"], root=root, path=path)

    # --- type ---
    if "type" in schema:
        types = schema["type"] if isinstance(schema["type"], list) else [schema["type"]]
        if not any(_type_ok(instance, t) for t in types):
            errs.append(f"{where}: 类型应为 {'/'.join(types)}，实际 {type(instance).__name__}")
            return errs

    if "enum" in schema:
        if not any(_equal(instance, e) for e in schema["enum"]):
            errs.append(f"{where}: 取值必须是 {schema['enum']} 之一，实际 {instance!r}")
    if "const" in schema:
        if not _equal(instance, schema["const"]):
            errs.append(f"{where}: 取值必须为 {schema['const']!r}，实际 {instance!r}")

    # --- string ---
    if isinstance(instance, str):
        if "minLength" in schema and len(instance) < schema["minLength"]:
            errs.append(f"{where}: 长度 {len(instance)} < {schema['minLength']}")
        if "maxLength" in schema and len(instance) > schema["maxLength"]:
            errs.append(f"{where}: 长度 {len(instance)} > {schema['maxLength']}")
        if "pattern" in schema and not re.search(schema["pattern"], instance):
            errs.append(f"{where}: 不匹配 pattern {schema['pattern']}，实际 {instance!r}")

    # --- number ---
    if isinstance(instance, (int, float)) and not isinstance(instance, bool):
        if "minimum" in schema and instance < schema["minimum"]:
            errs.append(f"{where}: {instance} < minimum {schema['minimum']}")
        if "maximum" in schema and instance > schema["maximum"]:
            errs.append(f"{where}: {instance} > maximum {schema['maximum']}")
        if "exclusiveMinimum" in schema and instance <= schema["exclusiveMinimum"]:
            errs.append(f"{where}: {instance} <= exclusiveMinimum")
        if "exclusiveMaximum" in schema and instance >= schema["exclusiveMaximum"]:
            errs.append(f"{where}: {instance} >= exclusiveMaximum")

    # --- array ---
    if isinstance(instance, list):
        if "minItems" in schema and len(instance) < schema["minItems"]:
            errs.append(f"{where}: 元素数 {len(instance)} < {schema['minItems']}")
        if "maxItems" in schema and len(instance) > schema["maxItems"]:
            errs.append(f"{where}: 元素数 {len(instance)} > {schema['maxItems']}")
        if schema.get("uniqueItems"):
            seen: list[Any] = []
            for i, item in enumerate(instance):
                if any(_equal(item, s) for s in seen):
                    errs.append(f"{where}[{i}]: 与其他元素重复")
                seen.append(item)
        sub = schema.get("items")
        if isinstance(sub, dict):
            for i, item in enumerate(instance):
                errs += validate(item, sub, root=root, path=f"{path}[{i}]")

    # --- object ---
    if isinstance(instance, dict):
        for key in schema.get("required", []):
            if key not in instance:
                errs.append(f"{where}: 缺少必填字段 {key}")
        props = schema.get("properties", {})
        for key, value in instance.items():
            if key in props:
                errs += validate(value, props[key], root=root, path=f"{path}.{key}" if path else key)
            else:
                extra = schema.get("additionalProperties", True)
                if extra is False:
                    errs.append(f"{where}: 不允许的字段 {key}")
                elif isinstance(extra, dict):
                    errs += validate(value, extra, root=root, path=f"{path}.{key}" if path else key)

    return errs


def load_schema(path: str) -> dict:
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)
