#!/usr/bin/env python3
"""Resource syntax and fixed-theme text contrast checks (not a rendering test)."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

RES = Path(__file__).resolve().parents[2] / "app/src/main/res"


def luminance(color):
    channels = [int(color[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    linear = [x / 12.92 if x <= .04045 else ((x + .055) / 1.055) ** 2.4 for x in channels]
    return sum(x * w for x, w in zip(linear, (.2126, .7152, .0722)))


class UiResourcesTest(unittest.TestCase):
    def test_xml_syntax(self):
        for path in RES.rglob("*.xml"):
            with self.subTest(path=str(path.relative_to(RES))):
                ET.parse(path)

    def test_fixed_palette_text_contrast(self):
        for qualifier in ("values", "values-night"):
            colors = {e.get("name"): e.text for e in ET.parse(RES / qualifier / "colors_expressive.xml").getroot()}
            for prefix in ("ui_", "ui_blue_", "ui_green_", "ui_pink_"):
                for role in ("primary", "secondary", "tertiary", "primary_container", "secondary_container", "tertiary_container", "surface", "surface_variant", "background", "error", "error_container"):
                    fg, bg = prefix + "on_" + role, prefix + role
                    if fg not in colors or bg not in colors:
                        continue
                    with self.subTest(theme=qualifier, foreground=fg, background=bg):
                        a, b = luminance(colors[fg]), luminance(colors[bg])
                        self.assertGreaterEqual((max(a, b) + .05) / (min(a, b) + .05), 4.5)


if __name__ == "__main__":
    unittest.main()
