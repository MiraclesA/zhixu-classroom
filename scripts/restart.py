import subprocess,json,os,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
pid=ROOT/'.local/server.pid'
if pid.exists():
    process=int(pid.read_text());subprocess.run(['powershell','-NoProfile','-Command',f'$p=Get-CimInstance Win32_Process -Filter "ProcessId={process}"; if($p -and $p.CommandLine -like "*zhixu-classroom*classroom-1.0.0.jar*"){{Stop-Process -Id {process} -Force}}'],capture_output=True)
from start import main
sys.argv.append('--build');main()
