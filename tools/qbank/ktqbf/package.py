"""KT-QBF 包读写：安全读取、可复现写入、规范化 digest。

设计要点：
1. 读取端从不信任 ZIP —— 先做结构与安全体检，再解压到内存（总量受限）。
2. 写入端固定条目顺序、时间戳、权限位和压缩参数，使同样内容必然产出同样字节。
3. digest 只由 path + 内容哈希决定，与 ZIP 元数据无关。
"""

from __future__ import annotations

import hashlib
import io
import json
import os
import re
import zipfile
from dataclasses import dataclass, field

FORMAT_ID = "knowledge-trainer-question-bank"
SUPPORTED_MAJOR = 1

# 安全红线
MAX_ENTRIES = 20000
MAX_ENTRY_BYTES = 50 * 1024 * 1024          # 单条目解压后 50 MiB
MAX_TOTAL_BYTES = 500 * 1024 * 1024         # 总解压 500 MiB
MAX_COMPRESSION_RATIO = 200                 # 压缩比上限
MAX_MEDIA_BYTES = 20 * 1024 * 1024
MAX_TOTAL_MEDIA_BYTES = 200 * 1024 * 1024

FIXED_DATE_TIME = (1980, 1, 1, 0, 0, 0)     # ZIP 能表示的最早时间，固定后字节可复现
ALLOWED_TOP_LEVEL = ("manifest.json", "questions/", "locales/", "media/", "enhancements/")

_DRIVE_RE = re.compile(r"^[A-Za-z]:")
_BAD_CHARS = re.compile(r"[\x00-\x1f\\]")


class PackageError(Exception):
    """结构性/安全性错误，携带稳定错误码，便于测试断言。"""

    def __init__(self, code: str, message: str, path: str = "", line: int | None = None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.path = path
        self.line = line

    def as_dict(self) -> dict:
        out = {"code": self.code, "message": self.message}
        if self.path:
            out["path"] = self.path
        if self.line is not None:
            out["line"] = self.line
        return out


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def normalize_package_digest(entries: dict[str, bytes]) -> str:
    """规范化 digest：只依赖 path 与内容哈希，与压缩方式、时间戳无关。"""
    lines = [f"{path} {sha256_bytes(entries[path])}\n" for path in sorted(entries)]
    return sha256_bytes("".join(lines).encode("utf-8"))


def safe_relpath(name: str) -> str:
    """校验 ZIP 条目名 / authoring 相对路径。返回规范化后的路径，非法则抛错。"""
    if not name:
        raise PackageError("ZIP_PATH_TRAVERSAL", "条目名为空")
    if _BAD_CHARS.search(name):
        raise PackageError("ZIP_PATH_TRAVERSAL", f"条目名含控制字符或反斜杠: {name!r}")
    if name.startswith("/") or _DRIVE_RE.match(name):
        raise PackageError("ZIP_PATH_TRAVERSAL", f"条目名为绝对路径: {name!r}")
    if ".." in name.split("/"):
        raise PackageError("ZIP_PATH_TRAVERSAL", f"条目名含上级目录引用: {name!r}")
    # NTFS 备用数据流 / 结尾冒号在跨平台解包时行为不一致
    if any(":" in part for part in name.split("/")):
        raise PackageError("ZIP_PATH_TRAVERSAL", f"条目名含冒号: {name!r}")
    norm = name.replace("//", "/")
    if norm.endswith("/"):
        norm = norm[:-1]
    return norm


def check_top_level_allowed(name: str, allowed: tuple[str, ...] = ALLOWED_TOP_LEVEL) -> None:
    """顶层路径白名单。默认是 .qbank 的；.qbext 传自己的（见 qbext.py）。

    两种包共用同一套 ZIP 安全检查，但**内容边界不同** ——
    扩展包里出现 questions/ 目录同样是错的，不该被"通用读取器"放过。
    """
    if not any(name == top or name.startswith(top) for top in allowed):
        raise PackageError(
            "ZIP_ENTRY_UNEXPECTED",
            f"不允许顶层路径 {name!r}（只允许 {' / '.join(allowed)}）",
        )


@dataclass
class ZipContents:
    """解包后的内存内容。files 为 path -> bytes（不含 manifest.json）。"""

    manifest: dict
    manifest_raw: bytes
    entries: dict[str, bytes] = field(default_factory=dict)

    @property
    def all_entries(self) -> dict[str, bytes]:
        out = dict(self.entries)
        out["manifest.json"] = self.manifest_raw
        return out

    def text(self, path: str) -> str:
        raw = self.entries.get(path)
        if raw is None:
            raise PackageError("FILE_MISSING", f"包内缺少 {path}", path)
        try:
            return raw.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise PackageError("NDJSON_INVALID", f"{path} 不是合法 UTF-8: {exc}", path) from exc


def _zip_name(info: zipfile.ZipInfo) -> str:
    # 非 UTF-8 文件名在 Windows 上会被 CP437 解码成乱码，这里还原原始字节再解 UTF-8
    raw_name = info.filename
    if not (info.flag_bits & 0x800):
        try:
            raw_name = info.orig_filename.encode("cp437").decode("utf-8")
        except (UnicodeEncodeError, UnicodeDecodeError):
            raw_name = info.orig_filename
    return raw_name


def _check_local_header(raw: bytes, zf: zipfile.ZipFile, info: zipfile.ZipInfo) -> None:
    """核对本地文件头与中央目录是否自洽。

    为什么必须做：`zipfile.read()` 只信任中央目录里的 file_size / CRC，
    而随附在本地头里的同名副本被忽略。攻击者（或损坏的下载）只要改中央目录的
    file_size，就能让"解出的字节"和"清单里的哈希"对不上却不报错 —— 校验就形同虚设。
    这里把两处元数据逐项比对，不一致直接拒绝。
    """
    offset = info.header_offset
    if offset + 30 > len(raw) or raw[offset : offset + 4] != b"PK\x03\x04":
        raise PackageError("ZIP_HEADER_MISMATCH", f"条目本地文件头无效: {info.filename}", info.filename)
    name_len = int.from_bytes(raw[offset + 26 : offset + 28], "little")
    extra_len = int.from_bytes(raw[offset + 28 : offset + 30], "little")
    local_crc = int.from_bytes(raw[offset + 14 : offset + 18], "little")
    local_csize = int.from_bytes(raw[offset + 18 : offset + 22], "little")
    local_usize = int.from_bytes(raw[offset + 22 : offset + 26], "little")
    local_flags = int.from_bytes(raw[offset + 6 : offset + 8], "little")

    # 置了 bit 3 时本地头的大小/CRC 为 0，真实值在数据描述符里，不能据此判错
    has_descriptor = bool(local_flags & 0x08)
    problems = []
    if not has_descriptor:
        if local_csize != info.compress_size:
            problems.append(f"压缩后大小 中央目录={info.compress_size} 本地头={local_csize}")
        if local_usize != info.file_size:
            problems.append(f"解压后大小 中央目录={info.file_size} 本地头={local_usize}")
        if local_crc != info.CRC:
            problems.append("CRC 不一致")
    if (local_flags & 0x01) != (info.flag_bits & 0x01):
        problems.append("加密标志不一致")

    local_name = raw[offset + 30 : offset + 30 + name_len]
    # 条目名两处存放的必须是同一串字节。置了 0x800（UTF-8 标志）时，头部存的是 UTF-8 字节，
    # 而 Python 已按 UTF-8 解码成 str，所以要 encode 回 UTF-8；未置标志时 Python 是按 cp437
    # 解的，要 encode 回 cp437。写反的后果不是"报错"而是抛 UnicodeEncodeError：
    # ASCII 名字在两套码表里字节相同，测试全绿；一旦出现中文文件名就直接崩栈。
    cen_name = (
        info.filename.encode("utf-8")
        if info.flag_bits & 0x800
        else info.filename.encode("cp437", "replace")
    )
    if local_name != cen_name:
        problems.append("条目名不一致")

    data_start = offset + 30 + name_len + extra_len
    if data_start + info.compress_size > len(raw):
        problems.append("数据区越界（文件被截断？）")

    if problems:
        raise PackageError(
            "ZIP_HEADER_MISMATCH",
            f"{info.filename} 的本地文件头与中央目录矛盾：" + "；".join(problems),
            info.filename,
        )


def read_package(path: str, allowed_top_level: tuple[str, ...] = ALLOWED_TOP_LEVEL) -> ZipContents:
    """安全读取包。任何可疑结构直接抛 PackageError，不做"尽力修复"。"""
    if not zipfile.is_zipfile(path):
        raise PackageError("ZIP_NOT_ZIP", "文件不是合法 ZIP（是否只改了扩展名？）")

    with open(path, "rb") as fh:
        raw = fh.read()

    with zipfile.ZipFile(path) as zf:
        infos = zf.infolist()
        if len(infos) > MAX_ENTRIES:
            raise PackageError("ZIP_BOMB", f"条目数 {len(infos)} 超过上限 {MAX_ENTRIES}")

        seen: set[str] = set()
        total_uncompressed = 0
        entries: dict[str, bytes] = {}
        manifest_raw: bytes | None = None

        for info in infos:
            name = _zip_name(info)
            if info.flag_bits & 0x1:
                raise PackageError("ZIP_ENCRYPTED", f"条目被加密，KT-QBF 不允许加密条目: {name}")
            # unix 模式高 16 位；符号链接为 0xA000
            mode = (info.external_attr >> 16) & 0xF000
            if mode == 0xA000:
                raise PackageError("ZIP_ENTRY_LINK", f"条目是符号链接: {name}")
            if mode not in (0, 0x8000, 0x4000):
                raise PackageError("ZIP_ENTRY_LINK", f"条目不是普通文件: {name}")

            if info.is_dir():
                # 空目录条目允许存在，但不参与内容校验
                safe_relpath(name)
                continue

            norm = safe_relpath(name)
            check_top_level_allowed(norm, allowed_top_level)
            if norm in seen:
                raise PackageError("ZIP_DUPLICATE_ENTRY", f"ZIP 中出现重复条目: {norm}", norm)
            seen.add(norm)

            if info.compress_size > 0 and info.file_size / info.compress_size > MAX_COMPRESSION_RATIO:
                raise PackageError(
                    "ZIP_BOMB",
                    f"{norm} 压缩比 {info.file_size / info.compress_size:.0f}:1 超过上限 {MAX_COMPRESSION_RATIO}:1",
                    norm,
                )
            if info.file_size > MAX_ENTRY_BYTES:
                raise PackageError("ZIP_BOMB", f"{norm} 单条目过大（{info.file_size} 字节）", norm)
            total_uncompressed += info.file_size
            if total_uncompressed > MAX_TOTAL_BYTES:
                raise PackageError("ZIP_BOMB", "解压总量超过上限", norm)

            if norm != "manifest.json":
                try:
                    _check_local_header(raw, zf, info)
                except PackageError:
                    raise
                except Exception as exc:  # noqa: BLE001
                    # 校验器不能把未预期异常甩给调用方：fail-closed 的含义是"给出报告并拒绝"，
                    # 不是"崩栈"。否则调用方拿到的是 traceback，连错误码都没有。
                    raise PackageError(
                        "ZIP_HEADER_MISMATCH",
                        f"{norm} 的本地文件头无法核对：{type(exc).__name__}: {exc}",
                        norm,
                    ) from exc

            data = zf.read(info)
            if norm == "manifest.json":
                manifest_raw = data
            else:
                entries[norm] = data

        if manifest_raw is None:
            raise PackageError("MANIFEST_MISSING", "包内缺少 manifest.json")

    try:
        manifest = json.loads(manifest_raw.decode("utf-8"))
    except UnicodeDecodeError as exc:
        raise PackageError("MANIFEST_INVALID", f"manifest.json 不是合法 UTF-8: {exc}", "manifest.json") from exc
    except json.JSONDecodeError as exc:
        raise PackageError("MANIFEST_INVALID", f"manifest.json 不是合法 JSON: {exc}", "manifest.json") from exc
    if not isinstance(manifest, dict):
        raise PackageError("MANIFEST_INVALID", "manifest.json 顶层必须是对象", "manifest.json")

    return ZipContents(manifest=manifest, manifest_raw=manifest_raw, entries=entries)


def build_package(entries: dict[str, bytes], manifest: dict) -> bytes:
    """可复现打包：固定顺序、固定时间戳、固定权限、固定压缩级别。

    manifest 由调用方提供（其 files 摘要必须已按 entries 算好）；
    这里只负责把它序列化成稳定字节。
    """
    manifest_bytes = (
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    ).encode("utf-8")

    all_entries = dict(entries)
    all_entries["manifest.json"] = manifest_bytes

    # manifest 第一条，其余按路径字典序 —— 保证字节级可复现
    ordered = ["manifest.json"] + sorted(p for p in all_entries if p != "manifest.json")

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for path in ordered:
            info = zipfile.ZipInfo(filename=path, date_time=FIXED_DATE_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            info.create_system = 3  # Unix，与 external_attr 的语义一致
            zf.writestr(info, all_entries[path])
    return buf.getvalue()


def write_package(entries: dict[str, bytes], manifest: dict, out_path: str) -> str:
    data = build_package(entries, manifest)
    os.makedirs(os.path.dirname(os.path.abspath(out_path)) or ".", exist_ok=True)
    with open(out_path, "wb") as fh:
        fh.write(data)
    return sha256_bytes(data)
