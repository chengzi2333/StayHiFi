"""Task C1：可复现的离线机型目录生成器（仅开发期使用，运行时零网络）。

把固定快照的 Google supported_devices.csv（UTF-16、tab 分隔）规范化成
确定性 gzip JSON 资产 + 可复现 metadata。同一份已验证字节永远生成逐字节
相同的产物；详见 docs/superpowers/plans/2026-10-27-device-names.md 与
spec §10。
"""

import argparse
import csv
import gzip
import hashlib
import io
import json
import os
import sys
import urllib.request

SOURCE_URL = "https://storage.googleapis.com/play_public/supported_devices.csv"

# Global Constraints 硬上限（docs/superpowers/plans/2026-10-27-device-names.md）。
SNAPSHOT_MAX_BYTES = 600_000      # 版本化 .csv.gz 快照
JSON_MAX_BYTES = 1_600_000        # 解压后 JSON
GZIP_MAX_BYTES = 400_000          # 随包资产 .json.gz

# 品牌规范化：Google CSV 的 "Oppo" 面客写法统一为 OPPO。
_CANONICAL_BRAND = {"oppo": "OPPO"}

# 子品牌面客名已自带品牌（ZTE 出品 nubia 设备），不得机械拼出 "ZTE nubia ..."。
_SUBBRAND_PREFIXES = ("nubia",)


def _normalize_ws(value):
    """trim 并合并连续空白。"""
    return " ".join((value or "").split())


def format_display_name(retail_branding, marketing_name):
    """把 Retail Branding + Marketing Name 规则化为面客名；无效输入返回 None。"""
    branding = _normalize_ws(retail_branding)
    marketing = _normalize_ws(marketing_name)
    if not marketing:
        return None
    if not branding:
        return marketing
    canonical = _CANONICAL_BRAND.get(branding.lower(), branding)
    first, _, rest = marketing.partition(" ")
    if first.lower() == branding.lower():
        # 营销名已带品牌前缀：去重并统一品牌写法（"Oppo OPPO Pad" -> "OPPO Pad"）。
        return canonical if not rest else f"{canonical} {rest}"
    if canonical.lower() == "zte" and first.lower() in _SUBBRAND_PREFIXES:
        # ZTE 子品牌（nubia）面客文案不含 ZTE 前缀。
        return marketing
    return f"{canonical} {marketing}"


def build_catalog(rows):
    """rows 为 DictReader 风格 dict；返回 (按键排序目录, 计数统计)。

    同 Model 同名归并；同 Model 异名整体剔除（不受 CSV 行序影响）；
    空 Model、空 Marketing Name、Marketing Name 等于 Model 的占位行均跳过。
    """
    catalog = {}
    conflicts = set()
    stats = {"source_rows": 0, "written": 0, "conflicts": 0, "empty": 0}
    for row in rows:
        stats["source_rows"] += 1
        model = _normalize_ws(row.get("Model", ""))
        if not model:
            stats["empty"] += 1
            continue
        # 占位行：Marketing Name 等于 Model（拼接品牌前判定），如 "Pixel 9 Pro"/"Pixel 9 Pro"。
        if _normalize_ws(row.get("Marketing Name", "")) == model:
            stats["empty"] += 1
            continue
        display = format_display_name(row.get("Retail Branding", ""), row.get("Marketing Name", ""))
        if display is None or display == model:
            stats["empty"] += 1
            continue
        if model in conflicts:
            continue
        existing = catalog.get(model)
        if existing is None:
            catalog[model] = display
        elif existing != display:
            del catalog[model]
            conflicts.add(model)
    stats["conflicts"] = len(conflicts)
    stats["written"] = len(catalog)
    return dict(sorted(catalog.items())), stats


def _sha256(data):
    return hashlib.sha256(data).hexdigest()


def _gzip_deterministic(data):
    """固定 mtime=0、不嵌入文件名，保证逐字节可复现。"""
    buf = io.BytesIO()
    with gzip.GzipFile(filename="", fileobj=buf, mode="wb", mtime=0) as f:
        f.write(data)
    return buf.getvalue()


def _load_raw_bytes(input_path):
    """读取 .csv / .csv.gz 输入，返回原始 CSV 字节流（hash 与解码都基于它）。"""
    with open(input_path, "rb") as f:
        data = f.read()
    if input_path.endswith(".gz"):
        data = gzip.decompress(data)
    return data


def _decode_csv(raw):
    try:
        text = raw.decode("utf-16")
    except UnicodeError:
        text = raw.decode("utf-16-le")
    reader = csv.DictReader(io.StringIO(text), delimiter=",")
    return reader


def _write(path, data):
    parent = os.path.dirname(path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)


def main(argv=None):
    parser = argparse.ArgumentParser(description="生成可复现离线机型目录资产")
    src = parser.add_mutually_exclusive_group(required=True)
    src.add_argument("--url", help="开发期从 Google 源下载（唯一允许联网的路径）")
    src.add_argument("--input", help="本地 .csv / .csv.gz 快照（gzip 会先解压再做字节 hash）")
    parser.add_argument("--snapshot-date", required=True)
    parser.add_argument("--source-sha256", required=True)
    parser.add_argument("--output", required=True, help="device_models.json.gz 资产路径")
    parser.add_argument("--metadata", required=True, help="device_models.meta.json 路径")
    parser.add_argument("--snapshot-gzip", help="hash 验证通过后写出的版本化 .csv.gz 快照")
    args = parser.parse_args(argv)

    # 1) 取得原始 CSV 字节并验证固定 SHA-256；验证失败一律不写任何产物。
    try:
        if args.url:
            with urllib.request.urlopen(args.url, timeout=60) as resp:
                raw = resp.read()
        else:
            raw = _load_raw_bytes(args.input)
    except Exception as e:  # 网络/IO/解压错误统一失败，不部分写盘
        print(f"error: 读取输入失败: {e}", file=sys.stderr)
        return 1
    actual_sha = _sha256(raw)
    if actual_sha != args.source_sha256:
        print(
            f"error: 源 SHA-256 不匹配，期望 {args.source_sha256}，实际 {actual_sha}"
            "（上游可能已变化；停止，不静默换数据日期）",
            file=sys.stderr,
        )
        return 1

    # 2) 版本化输入快照（固定 mtime=0），超硬上限失败。
    if args.snapshot_gzip:
        snapshot = _gzip_deterministic(raw)
        if len(snapshot) > SNAPSHOT_MAX_BYTES:
            print(f"error: 快照 gzip {len(snapshot)} bytes 超过上限 {SNAPSHOT_MAX_BYTES}", file=sys.stderr)
            return 1
        _write(args.snapshot_gzip, snapshot)

    # 3) 规范化生成目录与产物字节；预算校验通过前不写资产。
    catalog, stats = build_catalog(_decode_csv(raw))
    json_bytes = json.dumps(
        catalog, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
    ).encode("utf-8")
    gzip_bytes = _gzip_deterministic(json_bytes)
    if len(json_bytes) > JSON_MAX_BYTES:
        print(f"error: JSON {len(json_bytes)} bytes 超过上限 {JSON_MAX_BYTES}", file=sys.stderr)
        return 1
    if len(gzip_bytes) > GZIP_MAX_BYTES:
        print(f"error: gzip 资产 {len(gzip_bytes)} bytes 超过上限 {GZIP_MAX_BYTES}", file=sys.stderr)
        return 1

    meta = {
        "budgets": {
            "gzipMaxBytes": GZIP_MAX_BYTES,
            "jsonMaxBytes": JSON_MAX_BYTES,
            "snapshotMaxBytes": SNAPSHOT_MAX_BYTES,
        },
        "conflicts": stats["conflicts"],
        "empty": stats["empty"],
        "gzipBytes": len(gzip_bytes),
        "gzipSha256": _sha256(gzip_bytes),
        "jsonBytes": len(json_bytes),
        "jsonSha256": _sha256(json_bytes),
        "snapshotDate": args.snapshot_date,
        "sourceRows": stats["source_rows"],
        "sourceSha256": actual_sha,
        # 固定规范源；--input 离线复现同样须经 source-sha256 字节验证，
        # 故 metadata 不含本机路径，保证 URL/快照两条路径逐字节一致。
        "sourceUrl": SOURCE_URL,
        "written": stats["written"],
    }
    meta_bytes = (json.dumps(meta, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")

    _write(args.output, gzip_bytes)
    _write(args.metadata, meta_bytes)

    print(
        f"catalog: rows={stats['source_rows']} written={stats['written']} "
        f"conflicts={stats['conflicts']} empty={stats['empty']}"
    )
    print(f"json: {len(json_bytes)} bytes sha256={meta['jsonSha256']}")
    print(f"gzip: {len(gzip_bytes)} bytes sha256={meta['gzipSha256']}")
    for probe in ("PHZ110", "OPD2409", "NX741J"):
        if probe in catalog:
            print(f"{probe}={catalog[probe]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
