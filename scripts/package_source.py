from pathlib import Path
import zipfile
ROOT=Path(__file__).resolve().parents[1]
dest=ROOT.parent/'知序课堂_核心版源码.zip'
excluded={'.local','target','node_modules','.git','__pycache__'}
with zipfile.ZipFile(dest,'w',zipfile.ZIP_DEFLATED) as archive:
    for file in ROOT.rglob('*'):
        if file.is_file() and not any(part in excluded for part in file.relative_to(ROOT).parts) and file.suffix not in {'.log','.pyc'}:
            archive.write(file,'zhixu-classroom/'+file.relative_to(ROOT).as_posix())
with zipfile.ZipFile(dest) as archive:
    assert not any(any(p in n.split('/') for p in excluded) for n in archive.namelist())
print(f'Source package: {dest.name}, {dest.stat().st_size} bytes; credentials and database excluded')
