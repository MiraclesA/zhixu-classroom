import json, secrets, subprocess, time, socket, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
LOCAL=ROOT/'.local'; LOCAL.mkdir(exist_ok=True)
MYSQL=Path('D:/mysql-8.4.9-winx64')
def run():
    config=LOCAL/'database.json'
    if config.exists(): info=json.loads(config.read_text())
    else:
        info={'password':secrets.token_hex(24),'port':3317}
        config.write_text(json.dumps(info),encoding='utf-8')
    runtime=Path('D:/AnYeApps/zhixu-classroom-runtime'); runtime.mkdir(parents=True,exist_ok=True)
    data=runtime/'mysql-data'
    if not (data/'mysql').exists():
        r=subprocess.run([str(MYSQL/'bin/mysqld.exe'),'--no-defaults','--initialize-insecure',f'--basedir={MYSQL.as_posix()}',f'--datadir={data.as_posix()}'],capture_output=True)
        if r.returncode: raise RuntimeError('独立 MySQL 初始化失败；未修改旧实例')
    ini=runtime/'my.ini'
    ini.write_text(f'[mysqld]\nbasedir={MYSQL.as_posix()}\ndatadir={data.as_posix()}\nport=3317\nbind-address=127.0.0.1\nmysqlx=0\ncharacter-set-server=utf8mb4\ncollation-server=utf8mb4_0900_ai_ci\n',encoding='utf-8')
    sock=socket.socket(); connected=sock.connect_ex(('127.0.0.1',3317))==0; sock.close()
    if not connected:
        p=subprocess.Popen([str(MYSQL/'bin/mysqld.exe'),f'--defaults-file={ini.as_posix()}'],creationflags=subprocess.CREATE_NO_WINDOW)
        (LOCAL/'mysql.pid').write_text(str(p.pid))
        for _ in range(60):
            s=socket.socket(); ready=s.connect_ex(('127.0.0.1',3317))==0; s.close()
            if ready: break
            if p.poll() is not None: raise RuntimeError('独立 MySQL 启动失败')
            time.sleep(.5)
        else: raise RuntimeError('独立 MySQL 启动超时')
    marker=LOCAL/'mysql-provisioned'
    if not marker.exists():
        sql=f"ALTER USER 'root'@'localhost' IDENTIFIED BY '{info['password']}'; CREATE DATABASE zhixu CHARACTER SET utf8mb4; CREATE USER 'zhixu'@'localhost' IDENTIFIED BY '{info['password']}'; GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES,DROP ON zhixu.* TO 'zhixu'@'localhost';"
        result=subprocess.run([str(MYSQL/'bin/mysql.exe'),'--no-defaults','--protocol=TCP','--host=127.0.0.1','--port=3317','--user=root'],input=sql.encode(),capture_output=True)
        if result.returncode: raise RuntimeError('独立 MySQL 配置失败，凭据未输出')
        marker.write_text('created')
    print('独立 MySQL 已就绪：127.0.0.1:3317 / zhixu；凭据仅存本机 .local')
if __name__=='__main__': run()
