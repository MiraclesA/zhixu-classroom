from api_acceptance import sql,ROOT
import json
assert int(sql('SELECT COUNT(*) FROM leave_applications;'))>=6
assert sql('SELECT status FROM leave_applications WHERE id=2;')=='APPROVED'
assert sql("SELECT COUNT(*) FROM attendance WHERE session_id=49 AND student_id=1 AND status='LEAVE';")=='1'
(ROOT/'docs/重启持久化验收.json').write_text(json.dumps({'passed':True,'checks':['重启后申请仍存在','浏览器审批结果仍为 APPROVED','关联考勤仍为 LEAVE']},ensure_ascii=False,indent=2),encoding='utf-8')
print('PASS: application, browser approval and linked attendance survived restart')
