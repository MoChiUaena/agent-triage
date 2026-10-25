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
  // F2：等待与恢复——等待横幅、材料输入门控、恢复请求与提示。
  const waitingFixture={...fixture,id:"wait",state:"WAITING",message:"等待人工处理：缺少 Issue 材料。",resumes:0,tasks:[
    {id:"REPOSITORY",title:"读取仓库概览",state:"SUCCEEDED",message:"done",requires:[],after:[],references:[]},
    {id:"PR",title:"PR 审查",state:"SUCCEEDED",message:"done",requires:["REPOSITORY"],after:[],references:[]},
    {id:"ISSUE",title:"Issue 分诊",state:"WAITING",message:"缺少 Issue 材料",requires:["REPOSITORY"],after:[],errorCode:"WAIT_MATERIAL",references:[]},
    {id:"SUMMARY",title:"综合报告",state:"PENDING",message:"等待前置任务",requires:[],after:["CHECK"],references:[]}],
    data:{checks:[],summary:null}};
  window.workflowPanel.render(waitingFixture);
  assert.match(document.querySelector("#workflow-state").textContent,/等待人工处理/);
  assert.equal(document.querySelector("#workflow-resume-box").hidden,false);
  assert.equal(document.querySelector("#workflow-resume-issue-row").hidden,false,"A material wait must ask for the Issue number");
  assert.match(document.querySelector("#workflow-resume-hint").textContent,/等待 Issue 材料/);
  assert.match(document.querySelector("#workflow-resume-hint").textContent,/最多可恢复两次/);
  assert.equal(document.querySelector("#workflow-cancel").disabled,false,"Waiting workflows stay cancellable");
  assert.match(document.querySelector("#workflow-tasks").textContent,/等待人工处理/);
  const retryFixture={...waitingFixture,tasks:[...waitingFixture.tasks.slice(0,2),
    {id:"CI",title:"CI 排障",state:"WAITING",message:"暂时性读取失败",requires:["PR"],after:[],errorCode:"WAIT_RETRY",references:[]},
    {id:"SUMMARY",title:"综合报告",state:"PENDING",message:"等待前置任务",requires:[],after:["CHECK"],references:[]}]};
  window.workflowPanel.render(retryFixture);
  assert.equal(document.querySelector("#workflow-resume-issue-row").hidden,true,"A retry wait must not ask for material");
  assert.match(document.querySelector("#workflow-resume-hint").textContent,/可直接恢复重试/);
  window.workflowPanel.render(waitingFixture);
  let resumeCall=null;
  window.workflowPanel.selectRepository("first");
  context.fetch=async(url,options={})=>{
    if(url==="/api/development/workflows/wait")return {ok:true,json:async()=>waitingFixture};
    if(url==="/api/development/workflows/wait/resume"){resumeCall={url,options};return {ok:true,json:async()=>({...waitingFixture,state:"PLANNED",message:"已恢复执行（第 1 次）：恢复前已重新核对 PR base/head 未变化，并补充 Issue #2。",resumes:1})};}
    if(url==="/api/development/workflows")return {ok:true,json:async()=>[waitingFixture]};
    return {ok:true,json:async()=>[]};
  };
  await window.workflowPanel.openRun("wait");
  document.querySelector("#workflow-resume-issue").value="2";
  await document.querySelector("#workflow-resume").events.click();
  await new Promise(resolve=>setImmediate(resolve));
  assert.ok(resumeCall,"Resume must call the resume endpoint");
  assert.equal(resumeCall.options.body,JSON.stringify({issueNumber:2}));
  assert.equal(resumeCall.options.headers["X-Triage-Development"],"1");
  assert.equal(document.querySelector("#workflow-resume-box").hidden,true,"A resumed workflow no longer shows the waiting box");
  assert.match(document.querySelector("#workflow-result-message").textContent,/已恢复执行（第 1 次）/);
  console.log("Workflow UI passed: dependencies, partial failures, conflicts, safe text, waiting banners and material resume");
})().catch(error=>{console.error(error);process.exitCode=1;});
