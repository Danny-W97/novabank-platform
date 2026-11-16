"""Offline checks for the two-manual documentation layout and CC resource links."""
from html.parser import HTMLParser
from pathlib import Path
import re
import unittest
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parents[2]
MANUALS = {"General_Instruction.md", "General_Instruction.pl.md"}


def anchors(text):
    result = set(re.findall(r'<a\s+id="([^"]+)"', text))
    for heading in re.findall(r'^#{1,6}\s+(.+)$', text, re.MULTILINE):
        heading = re.sub(r'<[^>]+>', '', heading).replace('`', '')
        result.add(re.sub(r'[^\w\- ]', '', heading.lower()).replace(' ', '-'))
    return result


class Resources(HTMLParser):
    def __init__(self):
        super().__init__()
        self.urls = []

    def handle_starttag(self, tag, attributes):
        for key, value in attributes:
            if key in ('src', 'href') and value:
                self.urls.append(value)


class DocumentationLayoutTests(unittest.TestCase):
    def test_docs_contains_only_both_language_versions(self):
        self.assertEqual(MANUALS, {p.name for p in (ROOT / 'docs').iterdir()})
        self.assertTrue(all((ROOT / 'docs' / name).is_file() for name in MANUALS))

    def test_readmes_identify_complete_platform_manuals(self):
        for name in ('README.md', 'README.pl.md'):
            text = (ROOT / name).read_text(encoding='utf-8')
            for manual in MANUALS:
                self.assertIn('](docs/' + manual + ')', text)
            for product in ('NovaBank', 'NovaTech Shop', 'Control Center'):
                self.assertIn(product, text)
        self.assertIn('complete operating manual for the entire platform',
                      (ROOT / 'README.md').read_text(encoding='utf-8'))

    def test_manual_and_readme_local_markdown_links_exist(self):
        for source in [ROOT / 'README.md', ROOT / 'README.pl.md',
                       *(ROOT / 'docs' / name for name in sorted(MANUALS))]:
            text = source.read_text(encoding='utf-8')
            for target in re.findall(r'\]\(([^\s)]+)(?:\s+"[^"]*")?\)', text):
                url = urlsplit(target)
                if url.scheme or url.netloc:
                    continue
                destination = source.parent / unquote(url.path) if url.path else source
                with self.subTest(source=source.name, target=target):
                    self.assertTrue(destination.is_file(), str(destination))
                    if url.fragment and destination.suffix == '.md':
                        self.assertIn(unquote(url.fragment), anchors(destination.read_text(encoding='utf-8')))

    def test_interactive_guide_resources_remain_available(self):
        guide = ROOT / 'scripts/control-center/guide/index.html'
        parser = Resources()
        parser.feed(guide.read_text(encoding='utf-8'))
        for target in parser.urls:
            url = urlsplit(target)
            if url.scheme or url.netloc or not url.path:
                continue
            with self.subTest(target=target):
                self.assertTrue((guide.parent / unquote(url.path)).is_file())
        launcher = (ROOT / 'scripts/control-center/interactive-guide.sh').read_text(encoding='utf-8')
        self.assertIn('GUIDE="$SCRIPT_DIR/guide/index.html"', launcher)
        self.assertNotIn('docs/interactive-guide.html', launcher)


if __name__ == '__main__':
    unittest.main()
