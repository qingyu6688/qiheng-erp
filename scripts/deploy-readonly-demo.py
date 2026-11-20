"""在同一文件系统原子切换已校验的静态官网和演示，保留旧目录。"""
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import zipfile


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--site-root', type=Path, required=True)
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--sha256', required=True)
    args = parser.parse_args()
    root = args.site_root.resolve(strict=True)
    archive = args.archive.resolve(strict=True)
    if root.name != 'index' or root.parent != archive.parent.parent:
        raise ValueError('压缩包必须位于当前网站根目录同级的独立发布目录内。')
    if digest(archive) != args.sha256.lower():
        raise ValueError('发布包 SHA-256 不一致，未修改当前网站。')
    candidate = archive.parent / 'candidate'
    candidate.mkdir(exist_ok=False)
    with zipfile.ZipFile(archive) as bundle:
        for entry in bundle.infolist():
            path = PurePosixPath(entry.filename)
            if path.is_absolute() or '..' in path.parts or '\\' in entry.filename or stat.S_ISLNK(entry.external_attr >> 16):
                raise ValueError('发布包包含不安全的路径。')
        bundle.extractall(candidate)
    manifest = json.loads((candidate / 'demo-manifest.json').read_text(encoding='utf-8-sig'))
    for entry in manifest['files']:
        path = (candidate / entry['path']).resolve(strict=True)
        if not path.is_relative_to(candidate) or digest(path) != entry['sha256']:
            raise ValueError('静态文件校验失败，未修改当前网站。')
    html = (candidate / 'experience/index.html').read_text(encoding='utf-8')
    if '/experience/assets/' not in html or "connect-src 'none'" not in html:
        raise ValueError('演示入口路径或只读网络限制缺失。')
    if '鄂ICP备2026068130号-1' not in (candidate / 'index.html').read_text(encoding='utf-8'):
        raise ValueError('官网备案号缺失。')
    # 保留旧的指纹资源，避免切换时已打开的官网页面加载失败。
    for relative in ['assets', 'experience/assets']:
        old_assets, new_assets = root / relative, candidate / relative
        if old_assets.is_dir():
            for old in old_assets.rglob('*'):
                target = new_assets / old.relative_to(old_assets)
                if old.is_file() and not target.exists():
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(old, target)
    for path in [candidate, *candidate.rglob('*')]:
        path.chmod(0o755 if path.is_dir() else 0o644)
    if root.stat().st_dev != candidate.stat().st_dev:
        raise ValueError('候选目录与站点根目录不在同一文件系统。')
    libc = ctypes.CDLL(None, use_errno=True)
    rename = libc.renameat2
    rename.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    rename.restype = ctypes.c_int
    if rename(-100, os.fsencode(root), -100, os.fsencode(candidate), 2) != 0:
        raise OSError(ctypes.get_errno(), '原子切换失败，原网站目录保留。')
    print(json.dumps({'status': '已发布', 'version': manifest['version'], 'root': str(root), 'previous': str(candidate), 'files': len(manifest['files'])}, ensure_ascii=False))


if __name__ == '__main__':
    main()
