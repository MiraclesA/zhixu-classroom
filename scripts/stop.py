import subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
for file,marker in [('server.pid','*zhixu-classroom*classroom-1.0.0.jar*'),('mysql.pid','*zhixu-classroom-runtime*my.ini*')]:
    p=ROOT/'.local'/file
    if p.exists():
        ident=int(p.read_text());script=f'$p=Get-CimInstance Win32_Process -Filter "ProcessId={ident}"; if($p -and $p.CommandLine -like "{marker}"){{Stop-Process -Id {ident}}}'
        subprocess.run(['powershell','-NoProfile','-Command',script],capture_output=True,check=True)
print('已停止本项目进程；保留数据库和文件')
