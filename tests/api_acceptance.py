"""Acceptance checks against this project's isolated MySQL demo instance.
Only creates/updates synthetic demo lessons; never reads a user's real database.
"""
import sys,json,urllib.request,urllib.error,concurrent.futures,subprocess,datetime,base64,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]; BASE='http://127.0.0.1:8097/api/v1'
passed=[]
def check(name,test):
    assert test,name;passed.append(name);print('PASS '+name)
def call(path,method='GET',data=None,token='',expected=200):
    headers={'Authorization':'Bearer '+token}
    if data is not None:headers['Content-Type']='application/json'
    request=urllib.request.Request(BASE+path,data=None if data is None else json.dumps(data).encode(),headers=headers,method=method)
    try:
        with urllib.request.urlopen(request,timeout=10) as r: status=r.status;value=json.load(r)
    except urllib.error.HTTPError as e:status=e.code;value=json.load(e)
    assert status==expected, f'{path}: expected {expected}, got {status}; {value.get("message")}'
    return value.get('data',{})
def sql(command):
    pw=json.loads((ROOT/'.local/database.json').read_text())['password'];conf=Path('D:/AnYeApps/zhixu-classroom-runtime/test-client.cnf')
    conf.write_text(f'[client]\nuser=zhixu\npassword={pw}\nhost=127.0.0.1\nport=3317\nprotocol=TCP\ndefault-character-set=utf8mb4\n',encoding='utf-8')
    r=subprocess.run(['D:/mysql-8.4.9-winx64/bin/mysql.exe',f'--defaults-file={conf}','--batch','--skip-column-names','zhixu'],input=command.encode(),capture_output=True)
    if r.returncode:
        (ROOT/'.local/test-sql-error.log').write_bytes(r.stderr)
        raise RuntimeError('Acceptance SQL failed; details retained locally')
    return r.stdout.decode('utf-8').strip()
def login(account):return call('/auth/login','POST',{'account':account,'password':'123456'})['token']
def upload(token):
    png=base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/9X8AAAAASUVORK5CYII=');boundary=uuid.uuid4().hex
    body=f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="test.png"\r\nContent-Type: image/png\r\n\r\n'.encode()+png+f'\r\n--{boundary}--\r\n'.encode()
    req=urllib.request.Request(BASE+'/attachments',data=body,headers={'Authorization':'Bearer '+token,'Content-Type':'multipart/form-data; boundary='+boundary},method='POST')
    with urllib.request.urlopen(req) as r:return json.load(r)['data']['id']
def main():
    server=call('/health');check('MySQL health',server['database']=='MySQL');day=datetime.date.fromisoformat(server['serverTime'][:10])
    st=login('20240101');t=login('T2026001');v=login('V2026001');d=login('D2026001');c=login('C2026001');s2=login('20240102');other=login('OTHERTEACHER');otherst=login('OTHER001');otherv=login('OTHERVICE')
    check('Five distinct identities',len({call('/me',token=x)['role'] for x in [st,t,v,d,c]})==5)
    call('/auth/login','POST',{'account':'20240101','password':'wrong'},expected=401);check('Wrong password denied',True)
    call('/auth/register','POST',{'role':'dean','account':'ATTACK01','name':'test','password':'secure123'},expected=403);check('Privileged registration denied',True)
    call('/auth/register','POST',{'role':'teacher','account':'ATTACK02','name':'test','password':'secure123','code':'EDU2026'},expected=403);check('Browser invitation cannot grant role',True)
    sql("INSERT INTO lessons(course_id,lesson_date,period_no,start_at,end_at) VALUES(1,CURRENT_DATE(),6,NOW()-INTERVAL 2 MINUTE,NOW()+INTERVAL 43 MINUTE) ON DUPLICATE KEY UPDATE start_at=NOW()-INTERVAL 2 MINUTE,end_at=NOW()+INTERVAL 43 MINUTE;")
    sid=int(sql('SELECT id FROM lessons WHERE course_id=1 AND lesson_date=CURRENT_DATE() AND period_no=6;'))
    sql(f'DELETE FROM attendance WHERE session_id={sid};') # Only the synthetic test period created above.
    shifted=datetime.datetime.now().replace(microsecond=0)-datetime.timedelta(minutes=2);shifted_end=shifted+datetime.timedelta(minutes=45)
    moved=call(f'/lesson-sessions/{sid}/time','PATCH',{'startAt':shifted.isoformat(timespec='minutes'),'endAt':shifted_end.isoformat(timespec='minutes')},t)
    check('Teacher can adjust own lesson time',int(moved['id'])==sid)
    call(f'/lesson-sessions/{sid}/time','PATCH',{'startAt':shifted.isoformat(timespec='minutes'),'endAt':shifted_end.isoformat(timespec='minutes')},other,403);check('Other teacher cannot adjust lesson time',True)
    call(f'/lesson-sessions/{sid}/time','PATCH',{'startAt':shifted_end.isoformat(timespec='minutes'),'endAt':shifted.isoformat(timespec='minutes')},t,400);check('Lesson end must follow start',True)
    # Explicit synthetic classroom demonstrated by the UI; this is not a production time-control API.
    values=list(concurrent.futures.ThreadPoolExecutor(5).map(lambda _:call(f'/lesson-sessions/{sid}/check-in','POST',{},st),range(5)))
    check('Five concurrent check-ins remain one row',len({r['checked_at'] for r in values})==1 and sql(f'SELECT COUNT(*) FROM attendance WHERE session_id={sid} AND student_id=1;')=='1')
    check('Current check-in present',values[0]['status']=='PRESENT')
    sql(f"UPDATE lessons SET start_at=NOW()-INTERVAL 6 MINUTE,end_at=NOW()+INTERVAL 39 MINUTE WHERE id={sid};")
    late=call(f'/lesson-sessions/{sid}/check-in','POST',{},s2);check('Live late check-in',late['status']=='LATE')
    sql(f"UPDATE lessons SET start_at=NOW()-INTERVAL 31 MINUTE,end_at=NOW()+INTERVAL 14 MINUTE WHERE id={sid};")
    call(f'/lesson-sessions/{sid}/check-in','POST',{},s2,409);check('Live >30 minute check-in denied',True)
    sql(f"UPDATE lessons SET start_at=NOW()-INTERVAL 2 MINUTE,end_at=NOW()+INTERVAL 43 MINUTE WHERE id={sid};")
    sql("INSERT INTO lessons(course_id,lesson_date,period_no,start_at,end_at) VALUES(1,CURRENT_DATE(),5,NOW()+INTERVAL 1 HOUR,NOW()+INTERVAL 2 HOUR) ON DUPLICATE KEY UPDATE start_at=NOW()+INTERVAL 1 HOUR,end_at=NOW()+INTERVAL 2 HOUR;")
    second=int(sql('SELECT id FROM lessons WHERE course_id=1 AND lesson_date=CURRENT_DATE() AND period_no=5 LIMIT 1;'))
    check('Separate lessons remain independent',sql(f"SELECT COUNT(*) FROM attendance WHERE session_id={second} AND student_id=1 AND status='PRESENT';")=='0')
    call(f'/lesson-sessions/{sid}/attendance/1','PATCH',{'status':'LEAVE','reason':'test'},st,403);check('Student cannot correct attendance',True)
    call(f'/lesson-sessions/{sid}/attendance/1','PATCH',{'status':'LEAVE','reason':'test'},other,403);check('Teacher cannot edit another teaching assignment',True)
    call(f'/lesson-sessions/{sid}/attendance/1','PATCH',{'status':'ABSENT','reason':''},t,400);check('Correction reason required',True)
    call(f'/lesson-sessions/{sid}/attendance/1','PATCH',{'status':'ABSENT','reason':'synthetic correction'},t);check('Correction audit exists',int(sql(f"SELECT COUNT(*) FROM audit_logs WHERE action='ATTENDANCE:1' AND target_id={sid};"))>=1)
    call(f'/lesson-sessions/{sid}/scores','POST',{'studentId':1,'score':90,'remark':'Synthetic score'},t);check('Score saved',sql(f'SELECT score FROM scores WHERE session_id={sid} AND student_id=1;')=='90')
    call(f'/lesson-sessions/{sid}/scores','POST',{'studentId':1,'score':101,'remark':''},t,400);check('Invalid score rejected',True)
    call(f'/lesson-sessions/{sid}/scores','POST',{'studentId':1,'score':90.7,'remark':''},t,400);check('Fractional score rejected',True)
    call(f'/lesson-sessions/{sid}/hand-raises','POST',{'raised':True},st);check('Teacher sees hand',any(r['id']==1 and r['raised'] for r in call(f'/lesson-sessions/{sid}/seats',token=t)))
    body='private-'+uuid.uuid4().hex;call(f'/lesson-sessions/{sid}/messages','POST',{'body':body,'receiverId':101},st)
    check('Teacher receives private message',any(m['body']==body for m in call(f'/lesson-sessions/{sid}/messages',token=t)))
    check('Other students cannot read private message',not any(m['body']==body for m in call(f'/lesson-sessions/{sid}/messages',token=s2)))
    body='group-'+uuid.uuid4().hex;call(f'/lesson-sessions/{sid}/messages','POST',{'body':body},t);check('Group message visible to class',any(m['body']==body for m in call(f'/lesson-sessions/{sid}/messages',token=s2)))
    attachment=upload(st);check('Image uploaded privately',attachment>0)
    req=urllib.request.Request(BASE+f'/attachments/{attachment}',headers={'Authorization':'Bearer '+otherst})
    try:urllib.request.urlopen(req);raise AssertionError('attachment leaked')
    except urllib.error.HTTPError as e:check('Other student denied image',e.code==403)
    payload={'type':'short','startDate':str(day),'endDate':str(day),'sessionIds':[sid],'reason':'Synthetic leave'}
    aid=call('/leave/applications','POST',payload,st)['id'];call(f'/approvals/{aid}/decision','POST',{'decision':'APPROVED'},st,403);check('Student cannot approve',True)
    call(f'/approvals/{aid}/decision','POST',{'decision':'APPROVED'},t);check('Final approval changes absence to leave',sql(f'SELECT status FROM attendance WHERE session_id={sid} AND student_id=1;')=='LEAVE')
    call(f'/approvals/{aid}/decision','POST',{'decision':'APPROVED'},t,409);check('Repeated approval denied',True)
    payload.update(type='long',endDate=str(day+datetime.timedelta(days=2)))
    aid=call('/leave/applications','POST',payload,st)['id'];call(f'/lesson-sessions/{sid}/attendance/1','PATCH',{'status':'ABSENT','reason':'Synthetic staged test'},t)
    call(f'/approvals/{aid}/decision','POST',{'decision':'APPROVED'},d,409);check('Dean cannot skip teacher and vice',True)
    call(f'/approvals/{aid}/decision','POST',{'decision':'APPROVED'},t);check('Teacher alone cannot finish >2 day leave',sql(f'SELECT status FROM attendance WHERE session_id={sid} AND student_id=1;')=='ABSENT')
    call(f'/approvals/{aid}/decision','POST',{'decision':'APPROVED'},otherv,403);check('Other department vice denied',True)
    call(f'/approvals/{aid}/decision','POST',{'decision':'APPROVED'},v);check('Vice final approval updates selected lessons',sql(f'SELECT status FROM attendance WHERE session_id={sid} AND student_id=1;')=='LEAVE')
    payload.update(type='term',endDate=str(day+datetime.timedelta(days=119)),attachmentId=attachment)
    aid=call('/leave/applications','POST',payload,st)['id'];call(f'/approvals/{aid}/decision','POST',{'decision':'APPROVED'},t);call(f'/approvals/{aid}/decision','POST',{'decision':'APPROVED'},v)
    check('Term awaits dean after vice',sql(f'SELECT status FROM leave_applications WHERE id={aid};')=='PENDING_DEAN');call(f'/approvals/{aid}/decision','POST',{'decision':'APPROVED'},d);check('Three-stage term completed',sql(f'SELECT status FROM leave_applications WHERE id={aid};')=='APPROVED')
    payload.update(type='short',endDate=str(day));aid=call('/leave/applications','POST',payload,st)['id'];call(f'/lesson-sessions/{sid}/attendance/1','PATCH',{'status':'ABSENT','reason':'Synthetic rejection test'},t)
    call(f'/approvals/{aid}/decision','POST',{'decision':'REJECTED','reason':'Synthetic refusal'},t);check('Rejected leave does not change attendance',sql(f'SELECT status FROM attendance WHERE session_id={sid} AND student_id=1;')=='ABSENT')
    records=call(f'/reports/attendance?startDate={day}&endDate={day}',token=st);check('Student report is personal',all(r['student_id']==1 for r in records))
    records=call(f'/reports/attendance?startDate={day}&endDate={day}',token=c);check('Counselor report limited to department',not any(r['student_id']==200 for r in records))
    call(f'/lesson-sessions/{sid}/seats',token=otherst,expected=403);check('Classroom scope enforced',True)
    sql("INSERT IGNORE INTO rosters VALUES('20249998','名单测试',1,FALSE);")
    call('/auth/register','POST',{'role':'student','account':'20249998','name':'Wrong','password':'secure123'},expected=403);check('Roster identity verified',True)
    result=call('/auth/register','POST',{'role':'student','account':'20249999','name':'新同学','password':'secure123'},expected=200 if sql("SELECT COUNT(*) FROM users WHERE account='20249999';")=='0' else 409);check('Roster registration is server-side',True)
    sql("INSERT IGNORE INTO courses VALUES(7,'联测课程',201,1,'测试教室'); INSERT INTO lessons(course_id,lesson_date,period_no,start_at,end_at) VALUES(7,CURRENT_DATE(),7,NOW()-INTERVAL 2 MINUTE,NOW()+INTERVAL 43 MINUTE) ON DUPLICATE KEY UPDATE start_at=NOW()-INTERVAL 2 MINUTE,end_at=NOW()+INTERVAL 43 MINUTE;")
    sid2=int(sql('SELECT id FROM lessons WHERE course_id=7 AND lesson_date=CURRENT_DATE() AND period_no=7;'))
    multi={'type':'short','startDate':str(day),'endDate':str(day),'sessionIds':[sid,sid2],'reason':'Synthetic multi-teacher leave'}
    aid=call('/leave/applications','POST',multi,st)['id'];call(f'/approvals/{aid}/decision','POST',{'decision':'APPROVED'},t)
    check('All affected teachers must approve',sql(f'SELECT status FROM leave_applications WHERE id={aid};')=='PENDING_TEACHER')
    call(f'/approvals/{aid}/decision','POST',{'decision':'APPROVED'},other);check('Second teacher completes first-stage approval',sql(f'SELECT status FROM leave_applications WHERE id={aid};')=='APPROVED')
    sparse={'type':'long','startDate':str(day),'endDate':str(day+datetime.timedelta(days=2)),'sessionIds':[sid],'reason':'Synthetic sparse dates'}
    aid=call('/leave/applications','POST',sparse,s2)['id'];call(f'/approvals/{aid}/decision','POST',{'decision':'APPROVED'},t)
    check('Sparse dates use span for approval tier',sql(f'SELECT status FROM leave_applications WHERE id={aid};')=='PENDING_VICE_DEAN')
    call(f'/approvals/{aid}/decision','POST',{'decision':'APPROVED'},v)
    check('Unselected lesson remains untouched',sql(f"SELECT COUNT(*) FROM attendance WHERE session_id={sid2} AND student_id=2 AND status='LEAVE';")=='0')
    stream=urllib.request.urlopen(urllib.request.Request(BASE+f'/lesson-sessions/{sid}/events',headers={'Authorization':'Bearer '+t}),timeout=10)
    ready=stream.readline();stream.readline();stream.readline();call(f'/lesson-sessions/{sid}/hand-raises','POST',{'raised':False},st)
    event=stream.readline();stream.close();check('Real-time event after hand change',b'ready' in ready and b'refresh' in event)
    temp=login('20240103');call('/logout','POST',{},temp);call('/me',token=temp,expected=401);check('Logout invalidates server token',True)
    report={'tested_at':server['serverTime'],'passed':len(passed),'checks':passed,'database':'isolated MySQL 8.4','limitations':['WeChat developer tools and physical device not exercised','No deployment or public school data imported']}
    (ROOT/'docs/接口验收结果.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(f'{len(passed)} checks passed')
if __name__=='__main__':main()
