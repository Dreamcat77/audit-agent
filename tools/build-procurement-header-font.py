"""Build the self-hosted heading subset. Requires fonttools 4.66.1 + brotli 1.2.0.

Build dependencies belong in a workspace directory, never in the customer OS.
"""
import argparse
import hashlib
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

LABELS = "需求编号项目名称申请部门采购类型预算金额当前状态智能体预审询比价报警创建时间操作"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("license", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    font = instantiateVariableFont(TTFont(args.source), {"wght": 500}, inplace=True)
    # A modified font uses its own family name (OFL reserved-name requirement).
    names = {1: "Audit Header SC", 2: "Medium", 3: "AuditHeaderSC-500",
             4: "Audit Header SC Medium", 6: "AuditHeaderSC-Medium",
             16: "Audit Header SC", 17: "Medium"}
    for record in font["name"].names:
        if record.nameID in names:
            record.string = names[record.nameID].encode(record.getEncoding())
    codepoints = set(map(ord, LABELS)) | set(range(0x20, 0x7f))
    assert codepoints <= set(font.getBestCmap()), "Missing heading glyphs in source"
    options = subset.Options()
    options.flavor = "woff2"
    options.name_IDs = [0, 1, 2, 3, 4, 5, 6, 13, 14, 16, 17]
    options.name_legacy = True
    options.name_languages = [0x409]
    sub = subset.Subsetter(options=options)
    sub.populate(unicodes=codepoints)
    sub.subset(font)
    font.flavor = "woff2"
    args.output.mkdir(parents=True, exist_ok=True)
    target = args.output / "audit-header-sc-500.woff2"
    font.save(target)
    assert codepoints <= set(TTFont(target).getBestCmap())
    (args.output / "OFL.txt").write_bytes(args.license.read_bytes())
    (args.output / "SOURCE.md").write_text(
        "Noto Sans SC; Google Fonts / Adobe; SIL Open Font License 1.1.\n\n"
        "Source: https://github.com/google/fonts/tree/main/ofl/notosanssc\n\n"
        "Downloaded 2026-10-10. Static wght=500, renamed Audit Header SC; "
        "subset contains ASCII and the ten procurement table headings only. "
        "Other text continues to use the original site fonts.\n\n"
        f"Source TTF SHA-256: {hashlib.sha256(args.source.read_bytes()).hexdigest()}\n\n"
        f"WOFF2 SHA-256: {hashlib.sha256(target.read_bytes()).hexdigest()}\n",
        encoding="utf-8")
    print(f"HEADER_FONT_GLYPHS={len(codepoints)}; BYTES={target.stat().st_size}; LICENSE=OFL-1.1")


if __name__ == "__main__":
    main()
