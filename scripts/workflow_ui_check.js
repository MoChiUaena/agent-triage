"use strict";
const assert = require("node:assert/strict"), fs = require("node:fs"), path = require("node:path"), vm = require("node:vm");
class Element {
  constructor() { this.children=[]; this.text=""; this.value=""; this.events={}; this.open=false; }
  set textContent(value) { this.text=String(value??""); this.children=[]; }
  get textContent() { return this.text+this.children.map(child=>child.textContent).join(""); }
  set innerHTML(value) { throw new Error("Workflow data must be rendered as text"); }
  replaceChildren(...children) { this.children=children; this.text=""; }
  append(...children) { this.children.push(...children); }
  addEventListener(event,callback) { this.events[event]=callback; }
  scrollIntoView() {}
  showModal() { this.open=true; }
  close() { this.open=false; }
  classList={toggle(){}};
}
const nodes=new Map(), document={querySelector(selector){if(!nodes.has(selector))nodes.set(selector,new Element());return nodes.get(selector);},createElement:()=>new Element()};
const window={}, timers=new Map(); let timerId=0, resolveRun;
const fixture={id:"run",repositoryId:"first",repository:"acme/service",state:"COMPLETED_WITH_GAPS",message:"CI 未读取",createdAt:"2026-10-14T00:00:00Z",plan:{pull:{number:7,title:"<script>PR</script>",headSha:"a".repeat(40),baseSha:"b".repeat(40)}},tasks:[
  {id:"PR",title:"PR 审查",state:"FAILED",message:"版本变化",requires:["REPOSITORY"],after:[],references:[]},
  {id:"CI",title:"CI 排障",state:"SKIPPED",message:"前置任务未完成",requires:["PR"],after:[],references:[]},
  {id:"SUMMARY",title:"综合报告",state:"SUCCEEDED",message:"保留缺口",requires:[],after:["CHECK"],references:[]}],
  data:{checks:[{code:"CI_HEAD_MISMATCH",state:"CONFLICT",message:"CI 来自旧提交 <img>",sources:["PR","CI"]}],summary:{verdict:"INCOMPLETE",text:"材料不足",actions:["重新读取"],gaps:["权限不足"],sources:[],scope:"未新调用模型"}}};
const context=vm.createContext({window,document,URL,Date,console,encodeURIComponent,setTimeout:(fn)=>{timers.set(++timerId,fn);return timerId;},clearTimeout:id=>timers.delete(id),fetch:async(url)=>{
  if(url==="/api/development/workflows/run")return new Promise(resolve=>resolveRun=resolve);
  return {ok:true,json:async()=>[]};
}});
vm.runInContext(fs.readFileSync(path.join(__dirname,"../src/main/resources/static/workflow.js"),"utf8"),context);
window.workflowPanel.render(fixture);
assert.match(document.querySelector("#workflow-result-title").textContent,/<script>/);
assert.match(document.querySelector("#workflow-tasks").textContent,/已跳过/);
assert.match(document.querySelector("#workflow-tasks").textContent,/PR/);
assert.match(document.querySelector("#workflow-checks").textContent,/旧提交 <img>/);
assert.match(document.querySelector("#workflow-summary-state").textContent,/材料不完整/);
assert.equal(document.querySelector("#workflow-cancel").disabled,true);
(async()=>{
  window.workflowPanel.selectRepository("first");
  const pending=window.workflowPanel.openRun("run");
  window.workflowPanel.selectRepository("second");
  resolveRun({ok:true,json:async()=>fixture});await pending;
  assert.equal(document.querySelector("#workflow-result").hidden,true);
  assert.equal(timers.size,0);
  console.log("Workflow UI passed: dependencies, partial failures, conflicts, safe text and stale response cleanup");
})().catch(error=>{console.error(error);process.exitCode=1;});
