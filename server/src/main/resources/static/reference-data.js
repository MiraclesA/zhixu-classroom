/* Data bindings for the supplied HTML UI. Page templates and styles stay in app.js. */

// app.js 中的座位管理页最初把教室写死为“实训楼 A302”。
// 这里保留原页面模板，并改为按当前课次从 MySQL 的 courses.room 字段显示教室。
const classroomTemplate=window.classRoom;
window.classRoom=function classRoom(){
  const currentLesson=chooseSession();
  const room=currentLesson?.room||'暂未安排教室';
  return classroomTemplate().replace('实训楼 A302',E(room));
};

function clockText(value){return String(value).slice(11,16);}
function courseInfo(lesson){const visual=courseCatalog.find(c=>c.name===lesson.name);return {name:lesson.name,short:visual?.short||lesson.name,teacher:lesson.teacher,room:lesson.room,color:visual?.color||''};}
function courseAt(weekday,period){const monday=addDate(dateISO(),-dayNo(dateISO())+(page==='timetable'?weekShift:0)*7),day=addDate(monday,weekday);const lesson=server.lessons.find(l=>datePart(l.lesson_date)===day&&l.period_no===period);return lesson?courseInfo(lesson):null;}
function periodTime(period,fallback=timings[period]){const lesson=server.lessons.find(l=>datePart(l.lesson_date)===server.day&&l.period_no===period);return lesson?sessionTime(lesson):fallback;}
function lessonState(item){const lesson=server.lessons.find(l=>l.id===item.id),now=Date.now();if(!lesson)return '未安排';return now<Date.parse(lesson.start_at)?'待开始':now<Date.parse(lesson.end_at)?'课程中':'已结束';}
function isClassNow(){const now=Date.now();const lesson=server.lessons.find(l=>now>=Date.parse(l.start_at)&&now<Date.parse(l.end_at));if(!lesson)return null;return {period:lesson.period_no,course:courseInfo(lesson),minutes:(now-Date.parse(lesson.start_at))/60000};}
function currentClassName(){const row=server.records.find(r=>r.session_id===server.sid)||server.records.find(r=>role!=='student'||r.student_id===server.user?.id);return row?.class_name||'暂无班级课次';}
function lessonCaption(){const lesson=chooseSession();return lesson?`${lesson.name} · 第 ${lesson.period_no+1} 节`:'当前没有安排课程';}
function seatCount(status){return server.seats.filter(s=>s.status===status.toUpperCase()).length;}
function uiMetric(name){
  const attended=seatCount('present')+seatCount('late');
  const rows=visibleReport('week');
  const done=db.requests.filter(a=>a.log?.some(l=>l.role===role));
  const values={teacherClasses:new Set(server.lessons.map(l=>l.class_id)).size,todayLessons:getLessons(dateISO()).length,attended,rosterSize:people.length,attendanceRate:(people.length?attended/people.length*100:0).toFixed(1)+'%',weekPresent:rows.filter(r=>r.status==='PRESENT'||r.status==='LATE').length,messageCount:server.messages.filter(m=>m.sender_id!==server.user?.id).length,classCount:new Set(rows.map(r=>r.class_name)).size,studentCount:new Set(rows.map(r=>r.student_id)).size,weekLate:rows.filter(r=>r.status==='LATE').length,weekAbsent:rows.filter(r=>r.status==='ABSENT').length,reviewed:done.length,materials:activeRequests().filter(a=>a.attachment_id).length};
  return values[name]??0;
}
function visibleReport(scope=counselorScope){
  const start=scope==='week'?addDate(dateISO(),-dayNo(dateISO())):dateISO();
  const end=scope==='week'?addDate(start,6):start;
  return server.records.filter(r=>datePart(r.lesson_date)>=start&&datePart(r.lesson_date)<=end&&(scope!=='period'||r.period_no===selectedPeriod));
}
function fakeGroups(){
  const groupBy=page==='analytics'&&['major','grade'].includes(counselorTab)?counselorTab:'class_name';
  const groups=new Map();
  for(const row of visibleReport(page==='compare'?(compareFilter==='day'?'day':'week'):counselorScope)){
    const key=row[groupBy];if(!groups.has(key))groups.set(key,{name:row.class_name,major:row.major,grade:row.grade+'级',members:new Set(),presentIds:new Set(),lateIds:new Set(),absentIds:new Set(),leaveIds:new Set(),namesSet:new Set()});
    const g=groups.get(key);g.members.add(row.student_id);const prop={PRESENT:'presentIds',LATE:'lateIds',ABSENT:'absentIds',LEAVE:'leaveIds'}[row.status];if(prop)g[prop].add(row.student_id);if(['LATE','ABSENT'].includes(row.status))g.namesSet.add(row.student);
  }
  return [...groups.values()].map(g=>({...g,total:g.members.size,present:g.presentIds.size,late:g.lateIds.size,absent:g.absentIds.size,leave:g.leaveIds.size,names:[...g.namesSet].join('、')||'—'}));
}
async function setScope(scope){counselorScope=scope;render();}
async function searchStudent(){
  const q=studentQuery.trim(),node=document.getElementById('student-result');if(!q)return toast('请输入姓名或学号');
  const rows=visibleReport().filter(r=>r.account===q||r.student.includes(q));if(!rows.length){node.innerHTML=`<div class="notice-box">未找到「${E(q)}」，请核对姓名或学号。</div>`;return;}
  const first=rows[0],latest=rows[rows.length-1];
  node.innerHTML=`<div class="preview-mini"><div class="between wrap"><strong>${E(first.student)}</strong>${statusPill(latest.status.toLowerCase())}</div><p class="hint">学号：${E(first.account)} · ${E(first.class_name)} · 查询范围：${counselorScope==='period'?'第'+(selectedPeriod+1)+'节':counselorScope==='day'?'今天':'本周'}</p><div class="row wrap" style="margin-top:12px">${[['迟到','LATE','orange'],['旷课','ABSENT','red'],['请假','LEAVE','purple']].map(([name,state,color])=>`<span class="tag ${color}">${name} ${rows.filter(r=>r.status===state).length} 次</span>`).join('')}</div></div>`;
}
function replyStudent(){const message=[...server.messages].reverse().find(m=>m.sender_id!==server.user?.id&&server.seats.some(s=>s.id===m.sender_id));if(message)return message.sender;return people[1]||people[0]||'';}
function replyBody(){const who=replyStudent();return [...server.messages].reverse().find(m=>m.sender===who)?.body||'暂无学生反馈';}
