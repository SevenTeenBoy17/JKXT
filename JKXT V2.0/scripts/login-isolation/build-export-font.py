"""Offline, pinned full-glyph Noto Sans SC Regular build. Emits provenance to stdout."""
import argparse
import hashlib
import json
import platform
from pathlib import Path
import fontTools
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

PINS = {
    "NotoSansSC-variable.ttf": (17772300, "fb0637bafbcd804fe32152370a1225990745b4bc", "a3041811a78c361b1de50f953c805e0244951c21c5bd412f7232ef0d899af0da"),
    "OFL.txt": (4388, "1c9f43281b8f216c5461fe9ac729afbade7724e4", "1c05c68c34f9708415aada51f17e1b0092d2cea709bf4a94cd38114f9e73d7d9"),
    "METADATA.pb": (1053, "94723577311c60831fabdb3e0d4450d0ed6763a5", "3de4c75126b7b78011abb6773dd88ca3041ab512623681bd9234ef50019afc37"),
}
REQUIRED = set("head hhea maxp OS/2 hmtx cmap loca glyf name post".split())
VARIATION = set("fvar gvar avar cvar HVAR VVAR MVAR".split())
FIXED_TEXT = "演示·模拟数据监考安排表考试日期开始时间结束科目年级场工号教师角色来源主副系统排人工调整任务运行代次导出版本快照生成第页共条分配说明正式0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-:.,/()_ \""

def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def names(font):
    return {str(i): sorted({n.toUnicode() for n in font["name"].names if n.nameID == i})
            for i in (0, 1, 2, 4, 5, 6, 13, 14)}

def inspect(font):
    return {"tables": sorted(font.keys()), "glyphCount": font["maxp"].numGlyphs,
            "unicodeCmapCount": len(font.getBestCmap()), "nameIds": names(font),
            "headCreated": font["head"].created, "headModified": font["head"].modified,
            "fsType": font["OS/2"].fsType, "weight": font["OS/2"].usWeightClass}

def require(condition, message):
    if not condition:
        raise RuntimeError(message)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inputs", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    require(platform.python_version() == "3.12.14", "Unexpected Python version")
    require(fontTools.__version__ == "4.65.0", "Unexpected fontTools version")
    inputs = {}
    for name, (size, blob, sha256) in PINS.items():
        data = (args.inputs / name).read_bytes()
        actual_blob = hashlib.sha1(b"blob " + str(len(data)).encode("ascii") + b"\0" + data).hexdigest()
        require(len(data) == size and actual_blob == blob and hashlib.sha256(data).hexdigest() == sha256,
                "Pinned source mismatch: " + name)
        inputs[name] = {"bytes": size, "gitBlobSha1": blob, "sha256": sha256}
    wheel = args.inputs / "fonttools-4.65.0-py3-none-any.whl"
    require(wheel.stat().st_size == 1196441 and digest(wheel) == "3060b8c1fc2329fa20265b7c138614143ea7c1624e26c5c180c76aeb74deae6f", "Wheel mismatch")
    source = TTFont(args.inputs / "NotoSansSC-variable.ttf", recalcTimestamp=False, lazy=False, checkChecksums=2)
    require(source.sfntVersion == "\x00\x01\x00\x00" and REQUIRED | {"fvar", "gvar"} <= set(source.keys()), "Not full variable TrueType")
    require(source["OS/2"].fsType == 0, "Restricted embedding")
    axes = {a.axisTag: [a.minValue, a.defaultValue, a.maxValue] for a in source["fvar"].axes}
    require(set(axes) == {"wght"} and axes["wght"][0] <= 400 <= axes["wght"][2], "Unexpected axes")
    before = inspect(source)
    require(set(map(ord, "演示·模拟数据")) <= source.getBestCmap().keys(), "Missing source label glyphs")
    require(not args.output.exists(), "Refusing to overwrite output")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    static = instantiateVariableFont(source, {"wght": 400}, inplace=False, optimize=True, updateFontNames=True, static=True)
    static.recalcTimestamp = False
    static.save(args.output, reorderTables=True)
    saved = TTFont(args.output, recalcTimestamp=False, lazy=False, checkChecksums=2)
    saved.ensureDecompiled()
    require(saved.sfntVersion == "\x00\x01\x00\x00" and REQUIRED <= set(saved.keys()), "Missing TrueType tables")
    require(not ((VARIATION | {"CFF ", "CFF2"}) & set(saved.keys())), "Variable or CFF outlines remain")
    os2 = saved["OS/2"]
    require(os2.usWeightClass == 400 and os2.fsType == 0 and not (os2.fsSelection & 33) and bool(os2.fsSelection & 64), "Not unrestricted Regular")
    require(not (saved["head"].macStyle & 3), "Unexpected bold/italic style")
    for name_id, expected in {1: "Noto Sans SC", 2: "Regular", 4: "Noto Sans SC Regular", 6: "NotoSansSC-Regular"}.items():
        actual = {n.toUnicode() for n in saved["name"].names if n.nameID == name_id and n.platformID in (0, 3)}
        require(actual == {expected}, f"Unexpected name {name_id}: {actual}")
    require(saved["maxp"].numGlyphs == source["maxp"].numGlyphs, "Glyph loss")
    require(set(saved.getGlyphOrder()) <= saved["hmtx"].metrics.keys(), "Missing metrics")
    lost = sorted(set(source.getBestCmap()) - set(saved.getBestCmap()))
    require(not lost, "Unicode mapping loss")
    require(set(map(ord, FIXED_TEXT)) <= saved.getBestCmap().keys(), "Missing fixed export glyphs")
    require(saved["head"].modified == source["head"].modified, "Timestamp drift")
    print(json.dumps({"schemaVersion": 1, "family": "Noto Sans SC", "upstream": {
        "repository": "https://github.com/google/fonts", "commit": "2894aab31764f10f29c421bdfd2340d3b382d384",
        "directory": "ofl/notosanssc", "fontPath": "NotoSansSC[wght].ttf", "inputs": inputs},
        "transform": {"python": platform.python_version(), "fonttools": fontTools.__version__,
        "fonttoolsWheelSha256": digest(wheel), "axisLocation": {"wght": 400}, "recalcTimestamp": False,
        "updateFontNames": True, "static": True, "optimize": True, "reorderTables": True,
        "scriptSha256": digest(__file__)}, "source": before, "sourceAxes": axes,
        "output": {"path": "fonts/NotoSansSC-Regular.ttf", "bytes": args.output.stat().st_size,
        "sha256": digest(args.output), "lostUnicodeMappings": lost, **inspect(saved)}}, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
