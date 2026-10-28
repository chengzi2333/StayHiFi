"""Task C1：可复现离线机型目录生成器的失败测试（superpowers TDD RED）。"""

import gzip
import hashlib
import io
import json
import os
import tempfile
import unittest

import generate_catalog as gc

HEADER = ["Retail Branding", "Marketing Name", "Device", "Model"]


def _csv_bytes(rows):
    """构造带 BOM 的 UTF-16、逗号分隔 CSV，与 Google supported_devices.csv 同格式。"""
    lines = [",".join(HEADER)]
    for branding, marketing, device, model in rows:
        lines.append(",".join([branding, marketing, device, model]))
    return ("\n".join(lines) + "\n").encode("utf-16")


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _write_csv(path, rows):
    raw = _csv_bytes(rows)
    with open(path, "wb") as f:
        f.write(raw)
    return raw


def _write_csv_gz(path, rows):
    raw = _csv_bytes(rows)
    buf = io.BytesIO()
    with gzip.GzipFile(filename="", fileobj=buf, mode="wb", mtime=0) as f:
        f.write(raw)
    with open(path, "wb") as f:
        f.write(buf.getvalue())
    return raw


def _sample_rows():
    return [
        ("Oppo", "Pad 4 Pro", "OPD2409", "OPD2409"),
        ("Oppo", "OPPO Pad 4 Pro", "OPD2411", "OPD2411"),
        ("ZTE", "nubia Z80", "NX741J", "NX741J"),
        ("Oppo", "OPPO Find X7", "PHZ110", "PHZ110"),
        ("", "Pixel 9 Pro XL", "pixel", "Pixel 9 Pro"),
    ]


class FormatDisplayNameTest(unittest.TestCase):
    def test_brand_formatting_is_canonical_and_deduplicated(self):
        self.assertEqual(gc.format_display_name("Oppo", "Pad 4 Pro"), "OPPO Pad 4 Pro")
        self.assertEqual(gc.format_display_name("Oppo", "OPPO Pad 4 Pro"), "OPPO Pad 4 Pro")
        self.assertEqual(gc.format_display_name("Oppo", "Oppo Pad 4 Pro"), "OPPO Pad 4 Pro")
        self.assertEqual(gc.format_display_name("OPPO", "Pad 4 Pro"), "OPPO Pad 4 Pro")

    def test_zte_nubia_not_concatenated(self):
        self.assertEqual(gc.format_display_name("ZTE", "nubia Z80"), "nubia Z80")

    def test_empty_and_whitespace(self):
        self.assertIsNone(gc.format_display_name("Oppo", ""))
        self.assertIsNone(gc.format_display_name("Oppo", "   "))
        self.assertEqual(gc.format_display_name("", "Galaxy S24"), "Galaxy S24")
        self.assertEqual(gc.format_display_name("Oppo", "  Pad   4  Pro "), "OPPO Pad 4 Pro")


class BuildCatalogTest(unittest.TestCase):
    def test_build_catalog_maps_models(self):
        catalog, stats = gc.build_catalog(
            {"Retail Branding": b, "Marketing Name": m, "Device": d, "Model": mo}
            for b, m, d, mo in _sample_rows()
        )
        self.assertEqual(catalog["OPD2409"], "OPPO Pad 4 Pro")
        self.assertEqual(catalog["OPD2411"], "OPPO Pad 4 Pro")
        self.assertEqual(catalog["NX741J"], "nubia Z80")
        self.assertEqual(catalog["PHZ110"], "OPPO Find X7")
        self.assertEqual(catalog["Pixel 9 Pro"], "Pixel 9 Pro XL")

    def test_conflicting_models_are_skipped(self):
        rows = [
            {"Retail Branding": "Oppo", "Marketing Name": "OPPO Find X7", "Device": "a", "Model": "PHZ110"},
            {"Retail Branding": "Oppo", "Marketing Name": "OPPO Find X7 Pro", "Device": "b", "Model": "PHZ110"},
        ]
        catalog, stats = gc.build_catalog(rows)
        self.assertNotIn("PHZ110", catalog)
        self.assertEqual(stats["conflicts"], 1)
        self.assertEqual(stats["written"], 0)

    def test_same_model_same_name_merged(self):
        rows = [
            {"Retail Branding": "Oppo", "Marketing Name": "OPPO Find X7", "Device": "a", "Model": "PHZ110"},
            {"Retail Branding": "Oppo", "Marketing Name": "OPPO Find X7", "Device": "b", "Model": "PHZ110"},
        ]
        catalog, stats = gc.build_catalog(rows)
        self.assertEqual(catalog["PHZ110"], "OPPO Find X7")
        self.assertEqual(stats["written"], 1)
        self.assertEqual(stats["conflicts"], 0)

    def test_empty_and_placeholder_rows_skipped(self):
        rows = [
            {"Retail Branding": "Oppo", "Marketing Name": "", "Device": "a", "Model": "OPD1"},
            {"Retail Branding": "Oppo", "Marketing Name": "OPD2", "Device": "b", "Model": "OPD2"},
            {"Retail Branding": "Oppo", "Marketing Name": "x", "Device": "c", "Model": "  "},
        ]
        catalog, stats = gc.build_catalog(rows)
        self.assertEqual(catalog, {})
        self.assertEqual(stats["empty"], 3)
        self.assertEqual(stats["written"], 0)

    def test_model_is_trimmed(self):
        rows = [{"Retail Branding": "Oppo", "Marketing Name": "OPPO Find X7", "Device": "a", "Model": " PHZ110 "}]
        catalog, _ = gc.build_catalog(rows)
        self.assertEqual(catalog["PHZ110"], "OPPO Find X7")

    def test_unicode_preserved(self):
        rows = [{"Retail Branding": "", "Marketing Name": "小米 14 Pro", "Device": "a", "Model": "23127PN0CC"}]
        catalog, _ = gc.build_catalog(rows)
        self.assertEqual(catalog["23127PN0CC"], "小米 14 Pro")

    def test_stats_counts(self):
        rows = [
            {"Retail Branding": "Oppo", "Marketing Name": "OPPO Find X7", "Device": "a", "Model": "PHZ110"},
            {"Retail Branding": "Oppo", "Marketing Name": "", "Device": "b", "Model": "OPD1"},
        ]
        _, stats = gc.build_catalog(rows)
        self.assertEqual(stats["source_rows"], 2)
        self.assertEqual(stats["written"], 1)
        self.assertEqual(stats["empty"], 1)


class CliTest(unittest.TestCase):
    def _run(self, args):
        return gc.main(args)

    def _base_args(self, tmp, raw, input_path, extra=None):
        args = [
            "--input", input_path,
            "--snapshot-date", "2026-10-27",
            "--source-sha256", _sha256(raw),
            "--output", os.path.join(tmp, "out", "device_models.json.gz"),
            "--metadata", os.path.join(tmp, "out", "device_models.meta.json"),
        ]
        if extra:
            args.extend(extra)
        return args

    def test_wrong_sha_fails_and_no_snapshot_written(self):
        with tempfile.TemporaryDirectory() as tmp:
            csv_path = os.path.join(tmp, "in.csv")
            snap_path = os.path.join(tmp, "snap.csv.gz")
            _write_csv(csv_path, _sample_rows())
            rc = self._run([
                "--input", csv_path,
                "--snapshot-date", "2026-10-27",
                "--source-sha256", "0" * 64,
                "--output", os.path.join(tmp, "out.json.gz"),
                "--metadata", os.path.join(tmp, "out.meta.json"),
                "--snapshot-gzip", snap_path,
            ])
            self.assertEqual(rc, 1)
            self.assertFalse(os.path.exists(snap_path))
            self.assertFalse(os.path.exists(os.path.join(tmp, "out.json.gz")))
            self.assertFalse(os.path.exists(os.path.join(tmp, "out.meta.json")))

    def test_deterministic_byte_identical(self):
        with tempfile.TemporaryDirectory() as tmp:
            csv_path = os.path.join(tmp, "in.csv")
            raw = _write_csv(csv_path, _sample_rows())
            outputs = []
            metas = []
            for i in range(2):
                out = os.path.join(tmp, f"out{i}.json.gz")
                meta = os.path.join(tmp, f"out{i}.meta.json")
                rc = self._run(self._base_args(tmp, raw, csv_path) + ["--output", out, "--metadata", meta])
                self.assertEqual(rc, 0)
                with open(out, "rb") as f:
                    outputs.append(f.read())
                with open(meta, "rb") as f:
                    metas.append(f.read())
            self.assertEqual(outputs[0], outputs[1])
            self.assertEqual(metas[0], metas[1])

    def test_csv_and_csvgz_inputs_match(self):
        with tempfile.TemporaryDirectory() as tmp:
            csv_path = os.path.join(tmp, "in.csv")
            gz_path = os.path.join(tmp, "in.csv.gz")
            raw = _write_csv(csv_path, _sample_rows())
            _write_csv_gz(gz_path, _sample_rows())
            results = {}
            for name, path in (("csv", csv_path), ("csv.gz", gz_path)):
                out = os.path.join(tmp, f"{name}.json.gz")
                meta = os.path.join(tmp, f"{name}.meta.json")
                rc = self._run(self._base_args(tmp, raw, path) + ["--output", out, "--metadata", meta])
                self.assertEqual(rc, 0)
                with open(out, "rb") as f:
                    results[name] = f.read()
            self.assertEqual(results["csv"], results["csv.gz"])

    def test_snapshot_gzip_mtime_zero_size_cap(self):
        with tempfile.TemporaryDirectory() as tmp:
            csv_path = os.path.join(tmp, "in.csv")
            snap_path = os.path.join(tmp, "snap.csv.gz")
            raw = _write_csv(csv_path, _sample_rows())
            rc = self._run(self._base_args(tmp, raw, csv_path) + ["--snapshot-gzip", snap_path])
            self.assertEqual(rc, 0)
            with open(snap_path, "rb") as f:
                data = f.read()
            self.assertLessEqual(len(data), 600_000)
            self.assertEqual(int.from_bytes(data[4:8], "little"), 0, "gzip mtime must be 0")
            self.assertEqual(data[3] & 0x08, 0, "gzip must not embed a file name (FNAME)")
            self.assertEqual(gzip.decompress(data), raw)

    def test_output_gzip_is_valid_sorted_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            csv_path = os.path.join(tmp, "in.csv")
            raw = _write_csv(csv_path, _sample_rows())
            out = os.path.join(tmp, "out.json.gz")
            rc = self._run(self._base_args(tmp, raw, csv_path) + ["--output", out])
            self.assertEqual(rc, 0)
            with open(out, "rb") as f:
                data = f.read()
            text = gzip.decompress(data).decode("utf-8")
            catalog = json.loads(text)
            self.assertEqual(catalog["PHZ110"], "OPPO Find X7")
            self.assertEqual(catalog["OPD2409"], "OPPO Pad 4 Pro")
            self.assertEqual(catalog["NX741J"], "nubia Z80")
            # 紧凑分隔符 + 按键排序：序列化必须逐字符稳定。
            self.assertEqual(
                text,
                json.dumps(catalog, ensure_ascii=False, sort_keys=True, separators=(",", ":")),
            )

    def test_over_budget_fails(self):
        rows = [("Brand", "Name " + "x" * 120 + f" {i}", f"dev{i}", f"MODEL{i:05d}") for i in range(25_000)]
        with tempfile.TemporaryDirectory() as tmp:
            csv_path = os.path.join(tmp, "big.csv")
            raw = _write_csv(csv_path, rows)
            out = os.path.join(tmp, "out.json.gz")
            meta = os.path.join(tmp, "out.meta.json")
            rc = self._run(self._base_args(tmp, raw, csv_path) + ["--output", out, "--metadata", meta])
            self.assertEqual(rc, 1)
            self.assertFalse(os.path.exists(out))
            self.assertFalse(os.path.exists(meta))

    def test_metadata_has_no_timestamp_and_expected_keys(self):
        with tempfile.TemporaryDirectory() as tmp:
            csv_path = os.path.join(tmp, "in.csv")
            raw = _write_csv(csv_path, _sample_rows())
            meta = os.path.join(tmp, "out.meta.json")
            rc = self._run(self._base_args(tmp, raw, csv_path) + ["--metadata", meta])
            self.assertEqual(rc, 0)
            with open(meta, "rb") as f:
                doc = json.load(f)
            self.assertEqual(
                set(doc.keys()),
                {
                    "budgets", "conflicts", "empty", "gzipBytes", "gzipSha256",
                    "jsonBytes", "jsonSha256", "snapshotDate", "sourceRows",
                    "sourceSha256", "sourceUrl", "written",
                },
            )
            self.assertEqual(doc["snapshotDate"], "2026-10-27")
            self.assertEqual(doc["sourceSha256"], _sha256(raw))
            self.assertEqual(doc["written"], 5)
            self.assertLessEqual(doc["jsonBytes"], 1_600_000)
            self.assertLessEqual(doc["gzipBytes"], 400_000)


if __name__ == "__main__":
    unittest.main()
