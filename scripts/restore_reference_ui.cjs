// Remove the replacement layouts from the API adapter. The reference owns rendering.
const fs=require('fs'),path=require('path');
const acorn=require(require.resolve('acorn',{paths:['D:/高价值知识提炼/classroom-management-system_源码_20260918/frontend/node_modules']}));
const root=path.resolve(__dirname,'..');
const file=path.join(root,'server/src/main/resources/static/bridge.js');
const names=new Set(['homePage','classRoom','studentRoom','timetablePage','recordsPage','analyticsPage','comparePage','chatPage','chatMessages','openChat','studentSeat','seatAction','showRequest','review','showHelp','openNotifications','reportControls','reportTable','queryReport']);
let source=fs.readFileSync(file,'utf8');const ast=acorn.parse(source,{ecmaVersion:'latest'});
ast.body.filter(n=>n.type==='FunctionDeclaration'&&names.has(n.id.name)).sort((a,b)=>b.start-a.start).forEach(n=>source=source.slice(0,n.start)+source.slice(n.end));
fs.writeFileSync(file,source,'utf8');
console.log('Removed replacement view functions; reference templates will render all role pages and dialogs.');
