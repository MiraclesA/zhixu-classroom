import json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];(ROOT/'contracts').mkdir(exist_ok=True)
definitions=[
 ('GET','/health','MySQL 健康检查',[],None),
 ('POST','/auth/login','账号登录',[],{'account':'string','password':'string','role':'string'}),
 ('POST','/auth/register','学校名单核验注册',[],{'account':'string','name':'string','password':'string','role':'string','code':'string'}),
 ('POST','/auth/reset-request','登记人工核验找回请求',[],{'account':'string'}),
 ('GET','/me','身份和学校归属',[],None),('POST','/logout','服务端注销',[],{}),
 ('GET','/timetable','周课表',['weekStart'],None),('GET','/lesson-sessions/current','正在上课的课次',[],None),
 ('POST','/lesson-sessions/{sid}/check-in','逐课次报到（≤5正常，(5,30]迟到）',[],{}),
 ('PATCH','/lesson-sessions/{sid}/time','任课教师调整本节开始和结束时间',[],{'startAt':'string','endAt':'string'}),
 ('GET','/lesson-sessions/{sid}/seats','所属课次座位和考勤',[],None),
 ('PATCH','/lesson-sessions/{sid}/attendance/{student}','任课教师更正并留痕',[],{'status':'string','reason':'string'}),
 ('POST','/lesson-sessions/{sid}/hand-raises','举手或撤销',[],{'raised':'boolean'}),
 ('GET','/lesson-sessions/{sid}/messages','群聊及本人参与的私聊',[],None),
 ('POST','/lesson-sessions/{sid}/messages','发群聊或师生私聊',[],{'body':'string','receiverId':'integer'}),
 ('POST','/lesson-sessions/{sid}/scores','教师课堂评分（0–100整数）',[],{'studentId':'integer','score':'integer','remark':'string'}),
 ('GET','/lesson-sessions/{sid}/events','SSE 课次刷新事件（Bearer 鉴权）',[],None),
 ('GET','/leave/eligible-sessions','学生可选择的课次',['startDate','endDate'],None),
 ('POST','/leave/applications','提交申请（跨度和流程由服务端计算）',[],{'type':'string','startDate':'string','endDate':'string','sessionIds':'array','reason':'string','attachmentId':'integer'}),
 ('GET','/leave/applications/mine','权限范围内的申请及日志',[],None),
 ('GET','/approvals/pending','本人当前待办',[],None),
 ('POST','/approvals/{aid}/decision','当前节点审批',[],{'decision':'string','reason':'string'}),
 ('GET','/reports/attendance','权限范围内的考勤记录',['startDate','endDate','student?'],None),
 ('GET','/notifications','本人站内通知',[],None),
 ('POST','/attachments','私有 PNG/JPEG 上传（5MB）',[],None),('GET','/attachments/{aid}','受权限保护的附件',[],None)]
paths={}
for method,path,description,queries,body in definitions:
    parameters=[]
    for segment in path.split('/'):
        if segment.startswith('{'):parameters.append({'name':segment[1:-1],'in':'path','required':True,'schema':{'type':'integer','format':'int64'}})
    for q in queries:parameters.append({'name':q.rstrip('?'),'in':'query','required':not q.endswith('?'),'schema':{'type':'string'}})
    op={'summary':description,'parameters':parameters,'responses':{'200':{'description':'统一 code/message/data/requestId 响应；特殊流和图片见 content 类型'},'400':{'description':'参数无效'},'401':{'description':'未登录或凭据错误'},'403':{'description':'身份或数据范围无权限'},'409':{'description':'状态、重复或时间窗口冲突'}},'security':[] if path.startswith('/auth/') or path=='/health' else [{'bearerAuth':[]}]}
    if body is not None:
        props={k:{'type':v,**({'items':{'type':'integer'}} if v=='array' else {})} for k,v in body.items()}
        op['requestBody']={'required':True,'content':{'application/json':{'schema':{'type':'object','properties':props}}}}
    if path=='/attachments' and method=='POST':op['requestBody']={'required':True,'content':{'multipart/form-data':{'schema':{'type':'object','required':['file'],'properties':{'file':{'type':'string','format':'binary'}}}}}}
    if path.endswith('/events'):op['responses']['200']['content']={'text/event-stream':{'schema':{'type':'string'}}}
    if path=='/attachments/{aid}':op['responses']['200']['content']={'image/png':{'schema':{'type':'string','format':'binary'}},'image/jpeg':{'schema':{'type':'string','format':'binary'}}}
    paths.setdefault(path,{})[method.lower()]=op
spec={'openapi':'3.0.3','info':{'title':'知序课堂核心 API','version':'1.0.0','description':'固定时区 Asia/Shanghai (+08:00)。role=student/teacher/counselor/vice/dean；考勤=PRESENT/LATE/ABSENT/LEAVE。多个任课教师全部首审后才升级节点。'},'servers':[{'url':'http://127.0.0.1:8097/api/v1'}],'paths':paths,'components':{'securitySchemes':{'bearerAuth':{'type':'http','scheme':'bearer','description':'随机 256 位会话；服务端只存 SHA-256 哈希，12 小时到期。'}}}}
(ROOT/'contracts/openapi.yaml').write_text(json.dumps(spec,ensure_ascii=False,indent=2),encoding='utf-8')
print(f'{len(paths)} API paths recorded')
