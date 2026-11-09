"""Golden test: the Setup line for a phone that runs games through the Mali bridge (ledger row 128, bf5ccbd2b).

The 0.6.9 move brought back "%1$s is not an Adreno. The experimental Vulkan bridge is on ... Expect bugs",
which is wrong for MaliBridge (the bridge is the product, logs go from Setup > Diagnostics). The line reads
as in v0.5.4.2 again in English, Chinese (zh-CN, zh-TW, zh-HK) and Korean; this keeps the next re-port
from rewording it. Only the resource files are read.
"""
import unittest
from pathlib import Path
from xml.etree import ElementTree

RES = Path(__file__).resolve().parents[2] / 'app/src/main/res'
KEY = 'setup_gpu_bridge_detail'

# folder -> text the line must contain (the bridge is named, logs go through Setup > Diagnostics)
MUST = {
    'values': ('Mali bridge', 'Setup > Diagnostics'),
    'values-zh-rCN': ('Mali 桥接', '配置 > 诊断'),
    'values-zh-rTW': ('Mali 橋接', '設定 > 診斷'),
    'values-zh-rHK': ('Mali 橋接', '設定 > 診斷'),
    'values-ko': ('Mali 브리지', '설정 > 진단'),
}
# the 0.6.9 wording, per language: "not an Adreno", "experimental", "expect bugs"
BANNED = ('is not an Adreno', 'Expect bugs', '不是 Adreno', '实验性', '實驗性', 'Adreno가 아닙니다', '실험적')


def line(folder):
    root = ElementTree.parse(RES / folder / 'strings.xml').getroot()
    for node in root.iter('string'):
        if node.get('name') == KEY:
            return ''.join(node.itertext())
    raise AssertionError(f'{KEY} missing from {folder}')


class SetupGpuBridgeWordingTest(unittest.TestCase):
    def test_the_line_names_the_mali_bridge_and_setup_diagnostics(self):
        for folder, needles in MUST.items():
            with self.subTest(folder=folder):
                text = line(folder)
                self.assertIn('%1$s', text)
                for needle in needles:
                    self.assertIn(needle, text)

    def test_the_experimental_not_an_adreno_wording_does_not_come_back(self):
        for folder in MUST:
            with self.subTest(folder=folder):
                text = line(folder)
                for banned in BANNED:
                    self.assertNotIn(banned, text)


if __name__ == '__main__':
    unittest.main()
