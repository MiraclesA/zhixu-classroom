'use strict';
let server={user:null,lessons:[],seats:[],records:[],pending:[],notifications:[],messages:[],attachment:null,sid:null,day:dateISO(),busy:false,stream:null};
const TOKEN='zhixu_server_token';
function getToken(){return sessionStorage.getItem(TOKEN)||localStorage.getItem(TOKEN)||'';}
function clearToken(){sessionStorage.removeItem(TOKEN);localStorage.removeItem(TOKEN);}
async function api(path,method='GET',body){
  const headers={Authorization:'Bearer '+getToken()};
  if(body&&!(body instanceof FormData))headers['Content-Type']='application/json';
  const r=await fetch('/api/v1'+path,{method,headers,body:body?(body instanceof FormData?body:JSON.stringify(body)):undefined});
  const result=await r.json();if(!r.ok||result.code!==0){if(r.status===401&&authSession){clearToken();authSession=null;renderAuth();}throw Error(result.message||'请求失败');}return result.data;
}
function emptyDB(){return {requests:[],attendance:{},raised:false,messages:[],scores:{},notifications:[],demoOpen:false};}
db=emptyDB();people=[];
function persist(){} // Business state is written only through authenticated API calls.
function render(){renderShell();const ring=document.querySelector('.summary-ring');if(ring){const value=uiMetric('attendanceRate');ring.style.background=`conic-gradient(var(--mint) 0 ${value},#edf0f6 ${value} 100%)`;}}
async function handleAuthLogin(e){e.preventDefault();const button=document.getElementById('login-submit');if(button)button.disabled=true;try{
  const d=await api('/auth/login','POST',{...authState.login,role:authState.role});clearToken();(authState.remember?localStorage:sessionStorage).setItem(TOKEN,d.token);await signedIn(d.user);
}catch(error){authError(error.message);}finally{if(button)button.disabled=false;}}
async function handleAuthRegister(e){e.preventDefault();const r=authState.reg;if(r.password!==r.confirm)return authError('两次密码不一致');if(!r.agree)return authError('请先同意使用说明');try{
  await api('/auth/register','POST',{account:r.account,name:r.name,password:r.password,role:authState.regRole,code:r.code});authState.login={account:r.account,password:''};authState.role=authState.regRole;authState.screen='login';authState.message='学校名单核验成功，账号已创建，请登录。';authState.error='';renderAuth();
}catch(error){authError(error.message);}}
async function handleAuthForgot(e){e.preventDefault();try{const d=await api('/auth/reset-request','POST',authState.forgot);authState.message=d.message;authState.error='';renderAuth();}catch(error){authError(error.message);}}
async function logoutAuth(){try{await api('/logout','POST',{});}catch{}clearToken();authSession=null;server.user=null;server.stream?.abort();db=emptyDB();people=[];authState.login.password='';renderAuth();}
async function signedIn(user){server.user=user;role=user.role;authSession={...user};roles[role].person=user.name;roles[role].abbr=user.name.slice(0,1);page='home';
  document.getElementById('app').innerHTML='<div class="empty">正在读取学校课表…</div>';
  await loadLessons();await refreshAll();render();toast('已连接 MySQL，登录成功');}
async function loadLessons(){let start=addDate(dateISO(),-14),end=addDate(dateISO(),135);
  // Shared timetable access works for all five roles.
  let all=[];for(let offset=0;offset<150;offset+=7){const items=await api('/timetable?weekStart='+addDate(start,offset));all.push(...items);}server.lessons=[...new Map(all.map(l=>[l.id,l])).values()];
  const current=await api('/lesson-sessions/current');server.current=current;if(current.length){server.day=String(current[0].lesson_date).slice(0,10);selectedPeriod=current[0].period_no;}
  chooseSession();}
function datePart(x){return String(x).slice(0,10);}
function sessionTime(l){return `${String(l.start_at).slice(11,16)}–${String(l.end_at).slice(11,16)}`;}
async function refreshCurrentWeek(){const monday=addDate(dateISO(),-dayNo(dateISO())),sunday=addDate(monday,6),items=await api('/timetable?weekStart='+monday);server.lessons=[...server.lessons.filter(l=>datePart(l.lesson_date)<monday||datePart(l.lesson_date)>sunday),...items];}
function chooseSession(){const chosen=server.lessons.find(l=>datePart(l.lesson_date)===server.day&&l.period_no===selectedPeriod);server.sid=chosen?.id??null;return chosen;}
function convertRequest(a){return {...a,id:String(a.id),className:a.class_name,days:a.span_days,dates:[...new Set(a.targets.map(t=>datePart(t.lesson_date)))],slots:a.targets.map(t=>`${datePart(t.lesson_date)}_${t.period_no}`),created:a.created_at,status:a.status==='APPROVED'?'approved':a.status==='REJECTED'?'rejected':'pending',serverStatus:a.status,attachment:a.attachment_id?'已上传材料图片':'',log:a.log.map(l=>({...l,decision:l.decision==='APPROVED'?'approved':'rejected'}))};}
async function refreshAll(){if(!authSession||server.busy)return;server.busy=true;try{
  if(server.day!==dateISO()){server.day=dateISO();selectedPeriod=2;}
  await refreshCurrentWeek();
  const start=server.reportStart||addDate(dateISO(),-7),end=server.reportEnd||addDate(dateISO(),7);
  const queries=[api('/reports/attendance?startDate='+start+'&endDate='+end),api('/notifications')];
  if(role!=='counselor')queries.push(api('/leave/applications/mine'));if(['teacher','vice','dean'].includes(role))queries.push(api('/approvals/pending'));
  const d=await Promise.all(queries);server.records=d[0];db.notifications=d[1].map(n=>({body:n.body,time:n.created_at}));db.requests=role==='counselor'?[]:d[2].map(convertRequest);server.pending=['teacher','vice','dean'].includes(role)?d[3].map(a=>String(a.id)):[];
  await refreshRoom();
}finally{server.busy=false;}}
async function refreshRoom(){if(role==='student'){const actual=isClassNow();if(actual)selectedPeriod=actual.period;}const session=chooseSession();if(!session){server.seats=[];server.messages=[];db.messages=[];people=[];return;}server.seats=await api(`/lesson-sessions/${session.id}/seats`);people=server.seats.map(s=>s.name);
  db.attendance={};db.scores={};server.seats.forEach((s,i)=>{if(s.status!=='UNREPORTED')db.attendance[`${server.day}_${selectedPeriod}_${i}`]=s.status.toLowerCase();if(s.score!=null)db.scores[`${selectedPeriod}_${i}`]={score:s.score,remark:s.remark};});
  if(['student','teacher'].includes(role)){server.messages=(await api(`/lesson-sessions/${session.id}/messages`)).reverse();db.messages=server.messages.map(m=>({from:m.sender,to:m.receiver_id==null?'all':role==='student'?'teacher':m.receiver,body:m.body,time:clockText(m.created_at)}));const own=server.seats.find(s=>s.id===server.user.id);db.raised=!!own?.raised;if(role==='student'){delete db.attendance[`${server.day}_${selectedPeriod}_0`];if(own&&own.status!=='UNREPORTED')db.attendance[`${server.day}_${selectedPeriod}_0`]=own.status.toLowerCase();}connectEvents(session.id);}
}
async function action(fn,success){if(server.mutating)return;server.mutating=true;try{await fn();await refreshAll();render();if(success)toast(success);}catch(error){toast(error.message);}finally{server.mutating=false;}}
function activeRequests(){return db.requests.filter(a=>server.pending.includes(a.id));}
function noticeCount(){return server.pending.length;}

function getLessons(day){return server.lessons.filter(l=>datePart(l.lesson_date)===day).map(l=>({id:l.id,key:`${day}_${l.period_no}`,day,period:l.period_no,time:sessionTime(l),course:courseInfo(l)}));}
async function openLesson(id){const l=server.lessons.find(l=>l.id===id);if(!l)return;server.day=datePart(l.lesson_date);selectedPeriod=l.period_no;page='room';await action(()=>refreshRoom());}
async function selectDay(day){server.day=day;await action(()=>refreshRoom());}
async function setPeriod(value){selectedPeriod=Number(value);await action(()=>refreshRoom());}
function seatStatus(i){return server.seats[i]?.status.toLowerCase()||'unreported';}
sttxt.unreported='未报到';
function seatMap(){let html='';for(let row=0;row<5;row++)for(let col=0;col<10;col++){
  if(col===2||col===7){html+='<div class="seat-gap" title="过道"></div>';continue;}const i=row*8+col-(col>2?1:0)-(col>7?1:0),s=server.seats[i];if(!s){html+='<button class="seat unreported" disabled title="空座位"><span class="seat-person">—</span><span class="seat-name">空座位</span></button>';continue;}
  const messages=server.messages.filter(m=>m.sender_id===s.id&&m.receiver_id===server.user?.id).length;
  html+=`<button class="seat ${s.status.toLowerCase()}" title="${E(s.name)} · 第${row+1}行第${i%8+1}列" onclick="studentSeat(${i})" oncontextmenu="contextMenu(event,${i})" aria-label="${E(s.name)} ${E(sttxt[s.status.toLowerCase()])}"><span class="seat-person">${E(s.name.slice(0,1))}</span><span class="seat-name">${E(s.name)}</span>${s.raised?'<span class="seat-sign">✋</span>':''}${messages?`<span class="seat-message">${messages}</span>`:''}</button>`;
}return html;}


async function checkIn(){if(!server.sid)return toast('不是上课时间');await action(()=>api(`/lesson-sessions/${server.sid}/check-in`,'POST',{}),'报到已由服务端确认');}
async function toggleHand(){if(!server.sid)return toast('当前无课次');await action(()=>api(`/lesson-sessions/${server.sid}/hand-raises`,'POST',{raised:!db.raised}),'举手状态已同步');}
function toggleDemo(){toast('时间由服务器控制；普通用户不能改变上课时间');}


async function updateSeat(i,state){const reason=document.getElementById('change-reason')?.value.trim();if(!reason)return toast('请填写更正原因');await action(async()=>{await api(`/lesson-sessions/${server.sid}/attendance/${server.seats[i].id}`,'PATCH',{status:state.toUpperCase(),reason});closeModal();},'考勤已保存，更正原因已留痕');}
async function saveScore(i){const score=Number(document.getElementById('score-num').value),remark=document.getElementById('score-remark').value;if(!Number.isInteger(score)||score<0||score>100)return toast('评分需为 0–100 的整数');await action(async()=>{await api(`/lesson-sessions/${server.sid}/scores`,'POST',{studentId:server.seats[i].id,score,remark});closeModal();},'评分已保存');}
function inputTime(value){return String(value).replace(' ','T').slice(0,16);}
function openReschedule(){const lesson=chooseSession();if(!lesson)return toast('请选择需要调整的课次');showModal(`<p class="hint" style="margin-bottom:17px">调整后，学生课表和报到窗口将按新时间同步计算。</p><div class="form-block"><label class="label">开始时间</label><input id="lesson-start" class="field field-wide" type="datetime-local" value="${inputTime(lesson.start_at)}"></div><div class="form-block"><label class="label">结束时间</label><input id="lesson-end" class="field field-wide" type="datetime-local" value="${inputTime(lesson.end_at)}"></div><div class="modal-foot">${btn('取消',"closeModal()",'secondary')}${btn('保存上课时间',"saveReschedule()",'primary','check')}</div>`,'调整本节上课时间')}
async function saveReschedule(){const start=document.getElementById('lesson-start')?.value,end=document.getElementById('lesson-end')?.value;if(!start||!end)return toast('请填写开始和结束时间');await action(async()=>{const changed=await api(`/lesson-sessions/${server.sid}/time`,'PATCH',{startAt:start,endAt:end});const old=server.lessons.find(l=>l.id===changed.id)||{};server.lessons=[...server.lessons.filter(l=>l.id!==changed.id),{...old,...changed}];server.day=datePart(changed.lesson_date);selectedPeriod=changed.period_no;closeModal();},'上课时间已同步，学生将按新时间报到');}

async function shiftWeek(delta){weekShift+=delta;const d=new Date();const start=addDate(dateISO(),-(d.getDay()+6)%7+weekShift*7);await action(async()=>{const items=await api('/timetable?weekStart='+start);server.lessons=[...new Map([...server.lessons,...items].map(l=>[l.id,l])).values()];});}
async function setLeaveStart(value){leave.start=value;leave.selectedDays=[];leave.selectedLessons=[];await action(async()=>{const items=await api('/leave/eligible-sessions?startDate='+value+'&endDate='+addDate(value,119));server.lessons=[...new Map([...server.lessons,...items].map(l=>[l.id,l])).values()];});}
async function setAttachment(input){const file=input.files[0];if(!file)return;const data=new FormData();data.append('file',file);await action(async()=>{server.attachment=await api('/attachments','POST',data);leave.attachment=server.attachment.name;},'图片已上传至受保护存储');}
async function submitLeave(){const dates=effectiveDays();let keys=[...leave.selectedLessons];let end;
  if(leave.type==='term'){end=addDate(leave.start,119);keys=server.lessons.filter(l=>datePart(l.lesson_date)>=leave.start&&datePart(l.lesson_date)<=end).map(l=>`${datePart(l.lesson_date)}_${l.period_no}`);}
  else{const range=selectedDateRange();end=range[range.length-1];if(leave.type==='long')for(const day of dates.slice(0,-1))keys.push(...getLessons(day).map(l=>l.key));}
  const sessions=[...new Set(keys)].map(key=>server.lessons.find(l=>`${datePart(l.lesson_date)}_${l.period_no}`===key)?.id).filter(Boolean);
  if(!sessions.length)return toast('请选择存在的课次');await action(async()=>{await api('/leave/applications','POST',{type:leave.type,startDate:leave.start,endDate:end,sessionIds:sessions,reason:leave.reason,attachmentId:server.attachment?.id});leave.selectedLessons=[];leave.reason='';leave.attachment='';server.attachment=null;page='history';},'申请已提交；最终通过后更新所选课次考勤');}
async function approve(id,decision){const reason=document.getElementById('approval-comment')?.value.trim()||'';await action(async()=>{await api(`/approvals/${id}/decision`,'POST',{decision:decision==='approved'?'APPROVED':'REJECTED',reason});closeModal();},'审批结果已保存');}


async function showAttachment(id){try{const r=await fetch('/api/v1/attachments/'+id,{headers:{Authorization:'Bearer '+getToken()}});if(!r.ok)throw Error('无权读取图片');const url=URL.createObjectURL(await r.blob());const image=document.createElement('img');image.src=url;image.style.maxWidth='100%';image.onload=()=>URL.revokeObjectURL(url);document.querySelector('.modal')?.append(image);}catch(e){toast(e.message);}}









async function sendModalChat(){const body=document.getElementById('chat-modal-msg')?.value.trim();const who=modalData.who;const receiverId=who==='all'?null:who==='teacher'?chooseSession()?.teacher_id:server.seats.find(s=>s.name===who)?.id;if(!body)return toast('请输入消息');try{await api(`/lesson-sessions/${server.sid}/messages`,'POST',{body,receiverId});await refreshRoom();openChat(who);toast('消息已保存');}catch(e){toast(e.message);}}
async function sendPageChat(){const input=document.getElementById('chat-page-msg'),body=input?.value.trim();if(!body)return toast('请输入消息内容');if(!server.sid)return toast('当前没有所属课次');await action(()=>api(`/lesson-sessions/${server.sid}/messages`,'POST',{body,receiverId:role==='student'?chooseSession()?.teacher_id:null}),'消息已发送');}


function resetDemo(){toast('业务数据由 MySQL 保存；网页不能清空数据库');}
let connectedSid=null;
async function connectEvents(sid){if(connectedSid===sid)return;server.stream?.abort();connectedSid=sid;const abort=new AbortController();server.stream=abort;
  try{const response=await fetch(`/api/v1/lesson-sessions/${sid}/events`,{headers:{Authorization:'Bearer '+getToken()},signal:abort.signal});if(!response.ok)throw Error();const reader=response.body.getReader(),decoder=new TextDecoder();let buffer='';while(true){const {done,value}=await reader.read();buffer+=decoder.decode(value,{stream:true});if(done)break;const events=buffer.split('\n\n');buffer=events.pop();for(const event of events)if(event.includes('event:refresh')){await refreshCurrentWeek();await refreshRoom();if(['room','records','chat'].includes(page))render();}}}catch{}
  if(connectedSid===sid){connectedSid=null;if(authSession)setTimeout(()=>{if(server.sid===sid)connectEvents(sid);},2000);}
}
setInterval(async()=>{if(!authSession||document.hidden||document.querySelector('.modal'))return;try{await refreshAll();if(['home','approvals','history','room','chat'].includes(page)&&!document.querySelector('textarea:focus,input:focus'))render();}catch{}},15000);
(async()=>{if(getToken()){try{await signedIn(await api('/me'));return;}catch(e){clearToken();}}renderAuth();})();
