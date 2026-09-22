"""Generate app icon and deterministic test images; needs Pillow only for this script."""
from pathlib import Path
import math
from PIL import Image, ImageDraw

root = Path(__file__).resolve().parent.parent
(root / 'build').mkdir(exist_ok=True)
icon = Image.new('RGBA', (256, 256), (0, 0, 0, 0))
draw = ImageDraw.Draw(icon)
draw.rounded_rectangle((8, 8, 248, 248), radius=56, fill='#171c22')
draw.rounded_rectangle((47, 73, 209, 191), radius=23, outline='#f5c76a', width=12)
draw.rounded_rectangle((86, 53, 159, 82), radius=10, fill='#f5c76a')
draw.ellipse((88, 93, 168, 173), outline='#f5c76a', width=12)
draw.ellipse((179, 91, 188, 100), fill='#f5c76a')
draw.rounded_rectangle((73, 213, 183, 220), radius=3, fill='#f5c76a')
icon.save(root / 'public' / 'icon.png')
icon.save(root / 'build' / 'icon.ico', sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
fixtures = root / 'test-results' / 'fixtures'
fixtures.mkdir(parents=True, exist_ok=True)
scene = Image.new('RGB', (1200, 800))
draw = ImageDraw.Draw(scene)
for y in range(800):
    t = y / 800
    draw.line((0, y, 1200, y), fill=(int(93 + 94*t), int(150 + 39*t), int(178 - 23*t)))
draw.ellipse((866, 93, 988, 215), fill='#f6db9a')
draw.polygon([(0, 468), (167, 280), (326, 422), (581, 166), (841, 460), (1038, 302), (1200, 446), (1200, 800), (0, 800)], fill='#7b999b')
draw.polygon([(443, 313), (581, 166), (708, 310), (631, 281), (588, 312), (553, 264), (508, 312)], fill='#dfe1d5')
draw.polygon([(0, 572), (207, 468), (391, 555), (685, 386), (994, 560), (1200, 414), (1200, 800), (0, 800)], fill='#3d6b67')
draw.polygon([(0, 654), (170, 579), (363, 654), (597, 548), (807, 688), (1034, 548), (1200, 632), (1200, 800), (0, 800)], fill='#244a49')
scene.save(fixtures / 'landscape.jpg', quality=97)
scene.save(fixtures / 'landscape.png')
scene.save(fixtures / 'landscape.webp', quality=95)
transparent = Image.new('RGBA', (720, 960), (0, 0, 0, 0))
td = ImageDraw.Draw(transparent)
td.rounded_rectangle((120, 80, 600, 550), radius=50, fill=(83, 145, 172, 170))
td.ellipse((260, 180, 460, 380), fill=(245, 199, 106, 220))
transparent.save(fixtures / 'transparent.png')
transparent.save(fixtures / 'transparent.webp', lossless=True)
small = scene.resize((640, 400))
for orientation in range(1, 9):
    exif = Image.Exif()
    exif[274] = orientation
    small.save(fixtures / f'orientation-{orientation}.jpg', quality=95, exif=exif)
scene.resize((800, 200)).save(fixtures / 'panorama.png')
Image.new('RGB', (1, 1), '#637d9c').save(fixtures / 'tiny.png')
(fixtures / 'broken.jpg').write_bytes(b'not a jpeg')
frames = [Image.new('RGBA', (32, 32), color) for color in ('red', 'blue')]
frames[0].save(fixtures / 'animated.webp', save_all=True, append_images=frames[1:], duration=100, loop=0)
frames[0].save(fixtures / 'animated.png', save_all=True, append_images=frames[1:], duration=100, loop=0)
batch = fixtures / 'batch'
batch.mkdir(exist_ok=True)
batch_bytes = (fixtures / 'orientation-1.jpg').read_bytes()
for i in range(100):
    (batch / f'batch-{i:03d}.jpg').write_bytes(batch_bytes)
print(f'Generated icon and test fixtures in {fixtures}')
