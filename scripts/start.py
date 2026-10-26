import os,sys,json,subprocess,socket,time,webbrowser
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
from local_mysql import run as mysql
JAVA=Path('C:/Users/AnYe/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2')
MAVEN=Path('D:/高价值知识提炼/classroom-management-system_源码_20260918/tools/apache-maven-3.9.9/bin/mvn.cmd')
def main():
    mysql();env=os.environ.copy();env['JAVA_HOME']=str(JAVA);env['ZHIXU_DB_PASSWORD']=json.loads((ROOT/'.local/database.json').read_text())['password'];env['ZHIXU_DEMO']='true'
    env['ZHIXU_DB_URL']='jdbc:mysql://127.0.0.1:3317/zhixu?connectionTimeZone=%2B08:00&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8';env['ZHIXU_DB_USER']='zhixu';env['ZHIXU_BIND']='127.0.0.1';env['ZHIXU_PORT']='8097'
    if '--build' in sys.argv or not (ROOT/'server/target/classroom-1.0.0.jar').exists():
        result=subprocess.run([str(MAVEN),'-q','-f',str(ROOT/'server/pom.xml'),'package'],env=env)
        if result.returncode: raise SystemExit('构建失败，服务未启动')
    s=socket.socket(); running=s.connect_ex(('127.0.0.1',8097))==0;s.close()
    if not running:
        out=(ROOT/'.local/server.log').open('ab')
        p=subprocess.Popen([str(JAVA/'bin/java.exe'),'-jar',str(ROOT/'server/target/classroom-1.0.0.jar')],cwd=ROOT/'server',env=env,stdout=out,stderr=out,creationflags=subprocess.CREATE_NO_WINDOW)
        (ROOT/'.local/server.pid').write_text(str(p.pid))
    import urllib.request
    for _ in range(90):
        try:
            with urllib.request.urlopen('http://127.0.0.1:8097/api/v1/health',timeout=1) as r:
                if json.load(r).get('code')==0:break
        except Exception: time.sleep(.5)
    else:raise SystemExit('启动未就绪，请查看 .local/server.log')
    print('知序课堂已就绪：http://127.0.0.1:8097')
    if '--no-open' not in sys.argv:webbrowser.open('http://127.0.0.1:8097')
if __name__=='__main__':main()
