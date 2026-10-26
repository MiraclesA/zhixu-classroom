"""Record only the successful development call's actual token count, once."""
import sys,json,sqlite3
from pathlib import Path
from datetime import datetime
from zoneinfo import ZoneInfo
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,'C:/Users/AnYe/plugins/agent-quota-hub/scripts')
import core
data=json.loads((ROOT/'docs/API调用记录.json').read_text(encoding='utf-8'))
tokens=data['usage']['total_tokens']
assert data['provider']=='DeepSeek' and isinstance(tokens,int) and tokens>0
backup=ROOT/'.local/quota-before-token-record.sqlite3'
with sqlite3.connect(f'file:{core.DB_PATH.as_posix()}?mode=ro',uri=True) as source:
    with sqlite3.connect(backup) as destination:source.backup(destination)
marker='zhixu_business_review_20261014_once'
with sqlite3.connect(core.DB_PATH,timeout=10) as db:
    db.execute('BEGIN IMMEDIATE')
    if not db.execute('SELECT 1 FROM settings WHERE key=?',(marker,)).fetchone():
        db.execute('INSERT INTO usage_events(provider,metric,value,unit,cost_cny,source,note,recorded_at) VALUES(?,?,?,?,?,?,?,?)',('deepseek','tokens',tokens,'tokens',None,'API 实际响应','知序课堂业务约束评审',datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(timespec='seconds')))
        db.execute('INSERT INTO settings VALUES(?,?)',(marker,'true'))
print(f'额度管家已记录本次实际用量 {tokens} tokens；没有估算费用或改动服务配置')
