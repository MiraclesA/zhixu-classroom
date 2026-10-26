/* 知序课堂 · 登录与注册 UI 前端演示，不具备生产环境的身份验证安全性。 */
'use strict';
const AUTH_SESSION_KEY='zhixu_auth_session_v1';
const AUTH_USERS_KEY='zhixu_auth_users_v1';
const AUTH_ROLES={
 student:{label:'学生',name:'林同学',id:'20240101',p:'123456',icon:'graduate'},
 teacher:{label:'任课教师',name:'陈老师',id:'T2026001',p:'123456',icon:'book'},
 counselor:{label:'辅导员',name:'周老师',id:'C2026001',p:'123456',icon:'users'},
 vice:{label:'分院长',name:'李副院长',id:'V2026001',p:'123456',icon:'school'},
 dean:{label:'院长',name:'张院长',id:'D2026001',p:'123456',icon:'shield'}
};
function readAuthSession(){
  for(const storage of [sessionStorage,localStorage])try{
    const d=JSON.parse(storage.getItem(AUTH_SESSION_KEY));
    if(d && AUTH_ROLES[d.role] && d.account && d.name)return d;
  }catch(e){}
  return null;
}
let authSession=readAuthSession();
let authState={screen:'login',role:'student',regRole:'student',showPassword:false,showConfirm:false,remember:true,message:'',error:'', login:{account:'',password:''},reg:{name:'',account:'',className:'',code:'',password:'',confirm:'',agree:false},forgot:{account:''}};
const authField=(s)=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
function authDraft(where,key,value){authState[where][key]=value}
function authScreen(screen){authState.screen=screen;authState.error='';authState.message='';authState.showPassword=false;authState.showConfirm=false;renderAuth()}
function authChooseRole(r,reg=false){if(!AUTH_ROLES[r])return;if(reg)authState.regRole=r;else authState.role=r;authState.error='';renderAuth()}
function authToggle(which){if(which==='confirm')authState.showConfirm=!authState.showConfirm;else authState.showPassword=!authState.showPassword;renderAuth(); const id=which==='confirm'?'reg-confirm':authState.screen==='register'?'reg-password':'login-password';document.getElementById(id)?.focus()}
function authError(msg){authState.error=msg;authState.message='';renderAuth()}
function authDemoPick(roleName){const a=AUTH_ROLES[roleName];if(!a)return;authState.role=roleName;authState.login={account:a.id,password:a.p};authState.error='';authState.message='';renderAuth();document.getElementById('login-submit')?.focus()}
function getAuthUsers(){try{const d=JSON.parse(localStorage.getItem(AUTH_USERS_KEY));if(Array.isArray(d))return d}catch(e){}return []}
async function digestAuth(text){
  if(globalThis.crypto?.subtle){const bytes=new TextEncoder().encode(text);return [...new Uint8Array(await crypto.subtle.digest('SHA-256',bytes))].map(x=>x.toString(16).padStart(2,'0')).join('')}
  // 仅为离线静态原型回退实现。生产环境请使用服务端 Argon2id/bcrypt 和会话鉴权。
  let v=2166136261;for(const ch of text){v^=ch.charCodeAt(0);v=Math.imul(v,16777619)}return 'demo-'+(v>>>0).toString(16)
}
function authSalt(){if(globalThis.crypto?.getRandomValues){let a=new Uint8Array(16);crypto.getRandomValues(a);return [...a].map(x=>x.toString(16).padStart(2,'0')).join('')}return String(Date.now())+Math.random()}
function authGrant(account,roleName,name,isRegistered){
  const d={account,role:roleName,name,isRegistered:!!isRegistered,loginAt:Date.now()};
  sessionStorage.removeItem(AUTH_SESSION_KEY);localStorage.removeItem(AUTH_SESSION_KEY);
  (authState.remember?localStorage:sessionStorage).setItem(AUTH_SESSION_KEY,JSON.stringify(d));
  authSession=d;role=d.role;page='home';menuOpen=false;
  roles[role].person=d.name;roles[role].abbr=d.name.slice(0,1);
  render();window.scrollTo({top:0});toast('登录成功，欢迎回来！');
}
async function handleAuthLogin(event){
  event.preventDefault();
  const account=authState.login.account.trim(),pass=authState.login.password;
  if(!account||!pass){authError('请输入账号和密码后再登录。');return}
  const selected=authState.role, demo=AUTH_ROLES[selected];
  if(account.toUpperCase()===demo.id.toUpperCase() && pass===demo.p){authGrant(demo.id,selected,demo.name,false);return}
  const reg=getAuthUsers().find(u=>u.account.toLowerCase()===account.toLowerCase()&&u.role===selected);
  if(!reg){authError('账号或密码不正确，请检查身份选择，或使用下方演示账号。');return}
  const hash=await digestAuth(reg.salt+pass);
  if(hash!==reg.passHash){authError('账号或密码不正确，请重新输入。');return}
  authGrant(reg.account,reg.role,reg.name,true);
}
async function handleAuthRegister(event){
  event.preventDefault();
  const r=authState.reg,username=r.name.trim(),account=r.account.trim();
  if(!username||!account||!r.password||!r.confirm){authError('请先填写姓名、学号／工号及两次密码。');return}
  if(!/^[A-Za-z0-9]{6,20}$/.test(account)){authError('学号／工号需为 6–20 位英文字母或数字。');return}
  if(r.password.length<6||r.password.length>32){authError('密码长度应为 6–32 位。');return}
  if(r.password!==r.confirm){authError('两次输入的密码不一致。');return}
  if(authState.regRole==='teacher'&&r.code.trim().toUpperCase()!=='EDU2026'){authError('教师演示邀请码有误，请输入 EDU2026。');return}
  if(!r.agree){authError('请勾选同意演示项目的使用说明。');return}
  if(Object.values(AUTH_ROLES).some(u=>u.id.toLowerCase()===account.toLowerCase())||getAuthUsers().some(u=>u.account.toLowerCase()===account.toLowerCase())){authError('该账号已存在，请更换账号或返回登录。');return}
  const salt=authSalt();const passHash=await digestAuth(salt+r.password);
  const users=getAuthUsers();users.push({role:authState.regRole,account,name:username,className:r.className.trim(),salt,passHash});
  localStorage.setItem(AUTH_USERS_KEY,JSON.stringify(users));
  authState.role=authState.regRole;
  authState.login={account,password:''};
  authState.reg={name:'',account:'',className:'',code:'',password:'',confirm:'',agree:false};
  authState.screen='login';authState.error='';authState.message=`${username}，你的演示账号 ${account} 已创建，请输入密码登录。`;
  renderAuth();
}
function handleAuthForgot(e){e.preventDefault();const val=authState.forgot.account.trim();if(!val){authError('请输入需要找回的学号或工号。');return}authState.error='';authState.message='这是前端 UI 演示，没有连接学校短信或邮箱服务。请使用演示账号继续体验。';renderAuth()}
function authRemember(flag){authState.remember=flag}
function authAgree(flag){authState.reg.agree=flag}
function logoutAuth(){
  sessionStorage.removeItem(AUTH_SESSION_KEY);localStorage.removeItem(AUTH_SESSION_KEY);
  authSession=null;authState.screen='login';authState.error='';authState.message='你已安全退出演示账号。';authState.login={account:'',password:''};
  closeModal();renderAuth();window.scrollTo({top:0});
}
function authIcon(name,cls=''){return icon(name,cls)}
function authFieldInput({id,label,type='text',placeholder='',value='',name,section,autocomplete='',toggle='',note=''}){
 const eye=toggle?`<button type="button" class="auth-eye" onclick="authToggle('${toggle}')" aria-label="显示或隐藏密码">${authIcon(toggle==='confirm'?(authState.showConfirm?'eye':'eye'):(authState.showPassword?'eye':'eye'),'small')}</button>`:'';
 return `<div class="auth-field-group"><label for="${id}">${label}</label><div class="auth-input-wrap"><input id="${id}" class="auth-input" type="${type}" autocomplete="${autocomplete}" placeholder="${placeholder}" value="${authField(value)}" oninput="authDraft('${section}','${name}',this.value)" ${id==='login-account'?'autofocus':''}>${eye}</div>${note?`<small>${note}</small>`:''}</div>`
}
function authRoles(reg=false){let ids=reg?['student','teacher']:['student','teacher','counselor','vice','dean'];return `<div class="auth-role-list ${reg?'reg-roles':''}" role="group" aria-label="选择${reg?'注册':'登录'}身份">${ids.map(k=>`<button type="button" class="auth-role-item ${(reg?authState.regRole:authState.role)===k?'selected':''}" aria-pressed="${(reg?authState.regRole:authState.role)===k}" onclick="authChooseRole('${k}',${reg})">${authIcon(AUTH_ROLES[k].icon,'small')}<span>${AUTH_ROLES[k].label}</span></button>`).join('')}</div>`}
function authLogin(){return `<div class="auth-heading"><div class="auth-eyebrow">WELCOME BACK <span class="auth-eyebrow-line"></span></div><h1>欢迎回来<span class="auth-heading-dot">.</span></h1><p>使用校园账号，继续你的智慧课堂之旅</p></div><div class="auth-tabs"><button type="button" class="active" onclick="authScreen('login')">账号登录</button><button type="button" onclick="authScreen('register')">注册账号</button></div><form onsubmit="handleAuthLogin(event)" novalidate><div class="auth-field-group auth-role-group"><label>我是</label>${authRoles(false)}</div>${authFieldInput({id:'login-account',label:'学号 / 工号',placeholder:'请输入学号或教师工号',value:authState.login.account,name:'account',section:'login',autocomplete:'username'})}${authFieldInput({id:'login-password',label:'登录密码',type:authState.showPassword?'text':'password',placeholder:'请输入登录密码',value:authState.login.password,name:'password',section:'login',toggle:'password',autocomplete:'current-password'})}<div class="auth-form-options"><label class="auth-checkline"><input type="checkbox" ${authState.remember?'checked':''} onchange="authRemember(this.checked)"><span>记住登录状态</span></label><button type="button" class="auth-link" onclick="authScreen('forgot')">忘记密码？</button></div>${authFeedback()}<button id="login-submit" class="auth-submit" type="submit">登录智慧课堂 ${authIcon('arrow','small')}</button></form><div class="auth-demo"><div class="auth-demo-head"><div><span class="auth-demo-icon">${authIcon('spark','small')}</span><strong>快速体验演示账号</strong></div><small>点击填入账号与密码</small></div><div class="auth-demo-grid">${Object.entries(AUTH_ROLES).map(([k,r])=>`<button class="auth-demo-chip" onclick="authDemoPick('${k}')" type="button"><span>${r.label}</span><small>${r.id}</small></button>`).join('')}</div><div class="auth-demo-foot">所有演示账号密码统一为 <strong>123456</strong></div></div>`}
function authRegister(){const r=authState.reg;return `<div class="auth-heading auth-heading-reg"><div class="auth-eyebrow">START YOUR JOURNEY <span class="auth-eyebrow-line"></span></div><h1>创建新账号<span class="auth-heading-dot">.</span></h1><p>简单填写资料，开启有序的学习与教学</p></div><div class="auth-tabs"><button type="button" onclick="authScreen('login')">账号登录</button><button type="button" class="active" onclick="authScreen('register')">注册账号</button></div><form onsubmit="handleAuthRegister(event)" novalidate><div class="auth-field-group auth-role-group"><label>注册身份</label>${authRoles(true)}<small class="auth-hint">辅导员、分院长和院长账号由学校管理员分配。</small></div><div class="auth-reg-two">${authFieldInput({id:'reg-name',label:'真实姓名',placeholder:'请输入姓名',value:r.name,name:'name',section:'reg',autocomplete:'name'})}${authFieldInput({id:'reg-account',label:authState.regRole==='student'?'学号':'教师工号',placeholder:authState.regRole==='student'?'如 20260101':'如 T2026002',value:r.account,name:'account',section:'reg',autocomplete:'username'})}</div>${authState.regRole==='student'?authFieldInput({id:'reg-class',label:'所在班级（选填）',placeholder:'如 24软件技术1班',value:r.className,name:'className',section:'reg'}):authFieldInput({id:'reg-invite',label:'教师邀请码',placeholder:'输入教师注册邀请码',value:r.code,name:'code',section:'reg',note:'课程作业演示邀请码：EDU2026'})}<div class="auth-reg-two">${authFieldInput({id:'reg-password',label:'设置密码',type:authState.showPassword?'text':'password',placeholder:'至少 6 位',value:r.password,name:'password',section:'reg',toggle:'password',autocomplete:'new-password'})}${authFieldInput({id:'reg-confirm',label:'确认密码',type:authState.showConfirm?'text':'password',placeholder:'再次输入密码',value:r.confirm,name:'confirm',section:'reg',toggle:'confirm',autocomplete:'new-password'})}</div><label class="auth-checkline auth-reg-agree"><input type="checkbox" ${r.agree?'checked':''} onchange="authAgree(this.checked)"><span>我已阅读并同意 <a href="javascript:void(0)" onclick="showAuthRules()">演示项目使用说明</a></span></label>${authFeedback()}<button class="auth-submit" type="submit">创建账号 ${authIcon('arrow','small')}</button></form><p class="auth-bottom-prompt">已经有账号了？ <button class="auth-link" onclick="authScreen('login')">立即登录 ${authIcon('chevron','small')}</button></p>`}
function authForgot(){return `<div class="auth-heading"><div class="auth-eyebrow">ACCOUNT ASSISTANCE <span class="auth-eyebrow-line"></span></div><h1>找回登录密码<span class="auth-heading-dot">.</span></h1><p>按照学校账号找回流程，重新获取访问权限</p></div><div class="auth-forgot-art">${authIcon('shield','xl')}<h3>验证校园身份</h3><p>为保障课堂数据安全，正式系统需连接学校短信或邮箱验证服务。</p></div><form onsubmit="handleAuthForgot(event)">${authFieldInput({id:'forgot-account',label:'学号 / 工号',placeholder:'请输入你注册时的账号',value:authState.forgot.account,name:'account',section:'forgot',autocomplete:'username'})}${authFeedback()}<button class="auth-submit" type="submit">继续找回 ${authIcon('arrow','small')}</button></form><button class="auth-back" onclick="authScreen('login')">${authIcon('back','small')} 返回账号登录</button>`}
function authFeedback(){return `${authState.error?`<div class="auth-alert" role="alert">${authIcon('info','small')}<span>${authField(authState.error)}</span></div>`:''}${authState.message?`<div class="auth-success" role="status">${authIcon('check','small')}<span>${authField(authState.message)}</span></div>`:''}`}
function showAuthRules(){const r=document.getElementById('modal-layer');r.innerHTML=`<div class="modal-backdrop" onclick="if(event.target===this)closeModal()"><div class="modal"><div class="between"><h2 class="modal-title">演示项目使用说明</h2><button class="modal-close" onclick="closeModal()">×</button></div><p style="margin-top:18px;line-height:1.9;color:#718099">该页面仅用于课堂管理系统课程设计。注册账号数据暂时保存在当前浏览器中，登录认证、教师权限、用户隐私保护等尚未连接安全后端，不可用于正式教学或保存真实敏感资料。</p><div class="modal-foot"><button class="btn primary" onclick="closeModal()">知道了</button></div></div></div>`}
function authAside(){return `<aside class="auth-aside"><div class="auth-orbit auth-orbit-one"></div><div class="auth-orbit auth-orbit-two"></div><div class="auth-aside-inner"><div class="auth-brand"><span class="auth-brand-mark">${authIcon('book','big')}</span><div><div class="auth-brand-name">知序课堂<span class="auth-brand-dot">.</span></div><div class="auth-brand-en">SMART CLASSROOM</div></div></div><div class="auth-aside-content"><div class="auth-label"><span></span> 专注每一次课堂体验</div><h2>让每一次<br>课堂互动，<br><em>都有迹可循。</em></h2><p>从课表、考勤到请假审批，<br>让教学更轻松，让学习更有序。</p><div class="auth-preview"><div class="auth-preview-title"><span>${authIcon('calendar','small')} 今日课程</span><small>WED · 14 OCT</small></div><div class="auth-preview-line"><span class="auth-preview-time">08:20 <i></i> 10:00</span><span class="auth-preview-subj purp">Java Web 应用开发 <small>实训楼 A302</small></span></div><div class="auth-preview-line"><span class="auth-preview-time">10:10 <i></i> 11:50</span><span class="auth-preview-subj mint">计算机网络基础 <small>教学楼 B205</small></span></div><div class="auth-preview-line"><span class="auth-preview-time">14:30 <i></i> 16:10</span><span class="auth-preview-subj peach">数据库原理与应用 <small>教学楼 B303</small></span></div><div class="auth-floating"><span class="auth-float-icon">${authIcon('check')}</span><span><strong>签到成功</strong><small>已按时完成本节考勤</small></span><b>✓</b></div></div><div class="auth-side-pills"><span>${authIcon('check','small')} 智能考勤</span><span>${authIcon('calendar','small')} 周课表</span><span>${authIcon('file','small')} 分级请假</span></div></div><p class="auth-aside-footer">项目实训作品 · 用设计连接教与学 <span>© 2026 知序课堂</span></p></div></aside>`}
function renderAuth(){
 const root=document.getElementById('app');if(!root)return;
 document.body.classList.add('auth-view');closeModal?.();
 const content=authState.screen==='register'?authRegister():authState.screen==='forgot'?authForgot():authLogin();
 root.innerHTML=`<div class="auth-layout">${authAside()}<main class="auth-main"><div class="auth-mobile-brand"><div class="auth-brand-mark">${authIcon('book','big')}</div><div class="auth-brand-name">知序课堂<span class="auth-brand-dot">.</span></div></div><div class="auth-content">${content}</div><footer class="auth-footer"><div><span class="auth-footer-dot"></span> 系统运行正常 <span class="auth-footer-divider"></span> 校园课堂管理原型</div><span>课程设计 · 前端演示版</span></footer></main></div>`;
}
