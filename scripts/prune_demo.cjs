const fs=require('fs'),path=require('path');
const acorn=require(require.resolve('acorn',{paths:['D:/高价值知识提炼/classroom-management-system_源码_20260918/frontend/node_modules']}));
const root=path.resolve(__dirname,'..'),dir=path.join(root,'server/src/main/resources/static');
const bridge=acorn.parse(fs.readFileSync(path.join(dir,'bridge.js'),'utf8'),{ecmaVersion:'latest'});
const overridden=new Set(bridge.body.filter(n=>n.type==='FunctionDeclaration').map(n=>n.id.name));
const data=acorn.parse(fs.readFileSync(path.join(dir,'reference-data.js'),'utf8'),{ecmaVersion:'latest'});
data.body.filter(n=>n.type==='FunctionDeclaration').forEach(n=>overridden.add(n.id.name));
for(const filename of ['app.js','auth.js']){
  const file=path.join(dir,filename);let source=fs.readFileSync(file,'utf8');const ast=acorn.parse(source,{ecmaVersion:'latest'}),cuts=[];
  for(const n of ast.body){
    if(n.type==='FunctionDeclaration'&&(overridden.has(n.id.name)||['getDB','getAuthUsers','digestAuth','authSalt','authGrant','readAuthSession','syncApprovedLeave'].includes(n.id.name)))cuts.push([n.start,n.end]);
    if(n.type==='VariableDeclaration'&&n.declarations.some(d=>d.id.name==='seedRequests'))cuts.push([n.start,n.end]);
    if(n.type==='ExpressionStatement'&&n.expression.type==='AssignmentExpression'&&n.expression.left.name==='db')cuts.push([n.start,n.end]);
  }
  cuts.sort((a,b)=>b[0]-a[0]).forEach(([a,b])=>source=source.slice(0,a)+source.slice(b));
  fs.writeFileSync(file,source,'utf8');
}
console.log('Removed superseded browser authentication and mock business functions; retained original UI components.');
