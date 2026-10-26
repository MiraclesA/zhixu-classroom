// Independent reference checks: supplied CSS, icons, and ordered renderer class structure.
const fs=require('fs'),path=require('path'),crypto=require('crypto'),assert=require('assert');
const acorn=require(require.resolve('acorn',{paths:['D:/高价值知识提炼/classroom-management-system_源码_20260918/frontend/node_modules']}));
const root=path.resolve(__dirname,'..'),ref=path.join(root,'reference/知序课堂_UI'),live=path.join(root,'server/src/main/resources/static');
function parse(file){const text=fs.readFileSync(file,'utf8');return {text,ast:acorn.parse(text,{ecmaVersion:'latest'})};}
function functions(parsed){return new Map(parsed.ast.body.filter(n=>n.type==='FunctionDeclaration').map(n=>[n.id.name,n]));}
function classes(node,out=[]){if(!node||typeof node!=='object')return out;if(node.type==='TemplateElement'){const text=node.value.raw;for(const m of text.matchAll(/class="([^"]*)"/g))out.push(m[1].trim());return out;}for(const [key,value] of Object.entries(node))if(!['start','end'].includes(key)){if(Array.isArray(value))value.forEach(v=>classes(v,out));else if(value&&typeof value==='object')classes(value,out);}return out;}
const a=parse(path.join(ref,'app.js')),b=parse(path.join(live,'app.js')),original=functions(a),restored=functions(b);
const views=['heroBlock','stats','homePage','teacherHome','studentHome','counselorHome','adminHome','classRoom','studentRoom','timetablePage','leavePage','historyPage','approvalsPage','approvalItem','recordsPage','analyticsPage','comparePage','chatPage','chatMessages','openChat','studentSeat','seatAction','review','showRequest','showHelp','openNotifications'];
const checks=[];
for(const file of ['style.css','auth.css']){assert.equal(fs.readFileSync(path.join(ref,file),'utf8'),fs.readFileSync(path.join(live,file),'utf8'));checks.push(file+' byte-identical');}
for(const name of views){assert(restored.has(name),name+' must remain the reference renderer');let expected=classes(original.get(name)),actual=classes(restored.get(name));
  if(name==='seatAction'){assert.equal(actual.length,expected.length+2);const audit=actual.indexOf('textarea');assert(audit<actual.indexOf('grid grid2'));actual.splice(audit-1,2);} // Only the added audit label and textarea.
  // Attachment previews gain an authenticated image link; all original containers remain.
  if(['review','showRequest'].includes(name))actual=actual.filter(v=>v!=='link-btn');
  assert.deepStrictEqual(actual,expected,name+' ordered reference layout classes');checks.push(name+' reference layout');
}
for(const file of ['bridge.js','reference-data.js']){const methods=functions(parse(path.join(live,file)));for(const name of views)assert(!methods.has(name),file+' must not replace '+name);checks.push(file+' has no replacement views');}
const iconA=a.ast.body.find(n=>n.type==='VariableDeclaration'&&n.declarations.some(d=>d.id.name==='I')),iconB=b.ast.body.find(n=>n.type==='VariableDeclaration'&&n.declarations.some(d=>d.id.name==='I'));
assert.equal(a.text.slice(iconA.start,iconA.end).replaceAll('\r\n','\n'),b.text.slice(iconB.start,iconB.end).replaceAll('\r\n','\n'));checks.push('Original icon paths identical (line endings normalized)');
fs.writeFileSync(path.join(root,'docs/ui-qa/reference-contract.json'),JSON.stringify({passed:checks.length,checks,allowedChanges:['Server-backed values and accurate status copy','Audit reason field and authenticated attachment links'],reference:'Uploaded HTML/CSS/JavaScript source'},null,2));
console.log(checks.length+' supplied-source UI checks passed');
