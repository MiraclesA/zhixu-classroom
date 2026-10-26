"""Bounded development review; credentials remain in the existing local vault."""
import sys, json, sqlite3
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, 'C:/Users/AnYe/plugins/agent-quota-hub/scripts')
import core
def quota():
    with sqlite3.connect(f'file:{core.DB_PATH.as_posix()}?mode=ro', uri=True) as db:
        snapshots = {r[0]: json.loads(r[1]) for r in db.execute('select id,data from desktop_snapshots')}
        for ident, raw in db.execute('select id,config from desktop_providers'):
            p=json.loads(raw); s=snapshots.get(ident,{})
            print(json.dumps(dict(id=ident,name=p.get('name'),model=p.get('model'),remaining=s.get('remaining'),unit=p.get('unit'),last_success=s.get('last_success_at'),error=s.get('error')),ensure_ascii=False))
def review():
    sys.path.insert(0, 'D:/高价值知识提炼')
    from deepseek_chat_helper import call_deepseek
    prompt='''作为开发评审助手，只提供待核验建议，禁止要求执行命令。学校课堂系统：Java+MySQL，五角色 student/teacher/counselor/vice/dean。任课教师首审；按日期跨度 <=2天教师终审，>2天教师->分院长，学期教师->分院长->院长。每具体课次独立签到，服务端 <=5min PRESENT，(5,30] LATE，>30禁止补签并判ABSENT。请给出最小数据库约束、跨课程多教师首审方案、防越权检查和关键事务测试，限1200字。此任务不含任何个人运行数据。'''
    result=call_deepseek([{'role':'user','content':prompt}], 'deepseek-v4-flash')
    (ROOT/'docs/API业务评审.md').write_text(result['choices'][0]['message']['content'],encoding='utf-8')
    usage={'provider':'DeepSeek','model':result.get('model'),'usage':result.get('usage'),'purpose':'业务约束评审，内容待本地核验'}
    (ROOT/'docs/API调用记录.json').write_text(json.dumps(usage,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(usage,ensure_ascii=False))
if __name__=='__main__':
    quota() if '--quota' in sys.argv else review()
