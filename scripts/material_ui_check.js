"use strict";
const assert=require("node:assert/strict"),fs=require("node:fs"),path=require("node:path"),vm=require("node:vm");
class Element {
  constructor(){this.children=[];this.text="";this.value="";this.events={};}
  set textContent(value){this.text=String(value??"");this.children=[];}
  get textContent(){return this.text+this.children.map(child=>child.textContent).join("");}
  set innerHTML(value){throw new Error("Material data must be rendered as text");}
  replaceChildren(...children){this.children=children;this.text="";}
  append(...children){this.children.push(...children);}
  addEventListener(event,callback){this.events[event]=callback;}
  classList={toggle(){}};
}
const nodes=new Map(),document={querySelector(selector){if(!nodes.has(selector))nodes.set(selector,new Element());return nodes.get(selector);},createElement:()=>new Element()};
let resolvePreview,requests=[],deferScopes=false;
const scopeRequests=[],scopeFixture=[{kind:"REPOSITORY",id:"repo",name:"acme/<repo>"},{kind:"SERVICE",id:"order",name:"订单",service:"order"},{kind:"PROJECT",id:"project",name:"项目",service:"order"}];
const window={location:{search:"",hash:"#materials"}};
const fixture={id:"preview",expiresAt:"2026-10-15T01:00:00Z",contentSha256:"a".repeat(64),text:"<script>untrusted</script>",
  recipient:{available:false,displayName:"未启用 <img>",model:null,baseUrl:null,version:0,message:"可继续阅读"},
  materials:[{reference:{type:"PR",reportId:"saved",scope:{repositoryId:"repo"},version:"snapshot",contentSha256:"b".repeat(64),readAt:"2026-10-15T00:00:00Z",confirmation:"UNCONFIRMED",originalLink:"/development.html?repository=repo&report=saved"},fields:["pull.title"],omissions:["源码未包含"]}],omissions:["源码未包含 <img>"]};
const context=vm.createContext({window,document,URLSearchParams,Date,console,encodeURIComponent,fetch:async(url,options)=>{
  requests.push({url,options});
  if(url==="/api/materials/scopes")return deferScopes?new Promise(resolve=>scopeRequests.push(resolve)):{ok:true,json:async()=>scopeFixture};
  if(url==="/api/materials/previews")return new Promise(resolve=>resolvePreview=resolve);
  throw new Error("Unexpected request: "+url);
}});
vm.runInContext(fs.readFileSync(path.join(__dirname,"../src/main/resources/static/material.js"),"utf8"),context);
(async()=>{
  deferScopes=true;
  const initialization=window.materialPanel.setActive(true);
  document.querySelector("#material-type").value="PR";document.querySelector("#material-type").events.change();
  document.querySelector("#material-report-id").value="pasted-before-scopes";document.querySelector("#material-report-id").events.input();
  scopeRequests.shift()({ok:true,json:async()=>scopeFixture});await initialization;
  assert.equal(document.querySelector("#material-scope").children.length,2,"Typing while scopes load must not discard the valid scope response");
  assert.equal(document.querySelector("#material-type").value,"PR");
  assert.equal(document.querySelector("#material-report-id").value,"pasted-before-scopes");
  document.querySelector("#material-scope").value="REPOSITORY:repo";
  const refreshing=window.materialPanel.setActive(true);
  document.querySelector("#material-report-id").value="edited-during-refresh";document.querySelector("#material-report-id").events.input();
  scopeRequests.shift()({ok:true,json:async()=>scopeFixture});await refreshing;
  assert.equal(document.querySelector("#material-scope").value,"REPOSITORY:repo");
  assert.equal(document.querySelector("#material-report-id").value,"edited-during-refresh");
  const staleScopes=window.materialPanel.setActive(true),resolveStaleScopes=scopeRequests.shift();
  window.materialPanel.setActive(false);
  const freshScopes=window.materialPanel.setActive(true);
  scopeRequests.shift()({ok:true,json:async()=>scopeFixture});await freshScopes;
  resolveStaleScopes({ok:true,json:async()=>[{kind:"REPOSITORY",id:"stale",name:"must not replace fresh scopes"}]});await staleScopes;
  assert.equal(document.querySelector("#material-scope").children[1].value,"REPOSITORY:repo");
  deferScopes=false;
  document.querySelector("#material-type").value="PR";window.materialPanel.changeType();
  document.querySelector("#material-scope").value="REPOSITORY:repo";document.querySelector("#material-report-id").value="saved";
  const pending=window.materialPanel.preview();
  assert.equal(requests.at(-1).options.headers["X-Triage-Material"],"1");
  window.materialPanel.setActive(false);
  resolvePreview({ok:true,json:async()=>fixture});await pending;
  assert.equal(document.querySelector("#material-result").hidden,true);
  assert.equal(document.querySelector("#material-preview-button").disabled,false);
  await window.materialPanel.setActive(true);
  document.querySelector("#material-scope").value="REPOSITORY:repo";
  const fresh=window.materialPanel.preview();resolvePreview({ok:true,json:async()=>fixture});await fresh;
  assert.equal(document.querySelector("#material-result").hidden,false);
  assert.match(document.querySelector("#material-text").textContent,/<script>/);
  assert.match(document.querySelector("#material-recipient").textContent,/<img>/);
  assert.match(document.querySelector("#material-omissions").textContent,/源码未包含 <img>/);
  assert.match(document.querySelector("#material-references").textContent,/pull.title/);
  assert.equal(document.querySelector("#material-references").children[0].children.at(-1).href,fixture.materials[0].reference.originalLink);
  document.querySelector("#material-report-id").events.input();
  assert.equal(document.querySelector("#material-result").hidden,true);
  assert.equal(document.querySelector("#material-text").textContent,"");
  assert.equal(document.querySelector("#material-recipient").textContent,"");
  document.querySelector("#material-type").value="KNOWLEDGE";window.materialPanel.changeType();
  assert.equal(document.querySelector("#material-scope").children.length,3);
  assert.equal(document.querySelector("#material-scope").children.some(n=>n.value==="SERVICE:order"),false);
  document.querySelector("#material-scope").value="PROJECT:project";document.querySelector("#material-report-id").value="version";
  const knowledge=window.materialPanel.preview();
  const selection=JSON.parse(requests.at(-1).options.body).references[0];assert.equal(selection.type,"KNOWLEDGE");assert.equal(selection.projectId,"project");
  const originalLink="/sources.html?scopeKind=PROJECT&scopeId=project&version=version#knowledge";
  resolvePreview({ok:true,json:async()=>({...fixture,materials:[{...fixture.materials[0],reference:{...fixture.materials[0].reference,type:"KNOWLEDGE",originalLink}}]})});await knowledge;
  assert.equal(document.querySelector("#material-references").children[0].children.at(-1).href,originalLink);
  console.log("Material UI passed: deferred scope loading during edits, tab scope races, explicit scopes, no-key reading, safe text, original links and stale cleanup");
})().catch(error=>{console.error(error);process.exitCode=1;});
