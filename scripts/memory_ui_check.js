"use strict";
const assert=require("node:assert/strict"),fs=require("node:fs"),vm=require("node:vm");
class Element {
  constructor(tag){this.tagName=tag;this.children=[];this.value="";this.text="";this.events={};this.checked=false;this.disabled=false;}
  set textContent(v){this.text=String(v??"");this.children=[];} get textContent(){return this.text+this.children.map(c=>c.textContent).join("");}
  set innerHTML(v){throw Error("Untrusted memory must render as text");}
  append(...v){this.children.push(...v);}replaceChildren(...v){this.text="";this.children=v;}addEventListener(e,f){this.events[e]=f;}
}
const ok=value=>({ok:true,json:async()=>value});const tick=()=>new Promise(r=>setImmediate(r));
const version={id:"memory",scope:{kind:"PROJECT",id:"one"},revision:1,status:"CANDIDATE",bodySha256:"exact-hash",body:{topic:"<img topic>",content:"<script>content</script>",basis:{note:"user statement",references:[{saved:{reference:{originalLink:"javascript:alert(1)"},text:"<script>snapshot</script>"}}]}},updatedAt:"2026-10-24"};
function harness(search="",registered=true){
 const nodes=new Map(),pending=[],posts=[];
 const document={querySelector(s){if(!nodes.has(s))nodes.set(s,new Element());return nodes.get(s);},createElement(t){return new Element(t);}};
 const window={location:{search},confirm:()=>true};
 vm.runInContext(fs.readFileSync("src/main/resources/static/memory.js","utf8"),vm.createContext({document,window,URLSearchParams,encodeURIComponent,console,fetch:async(url,o={})=>{
  if(url==="/api/memories/scopes")return ok(registered?[{kind:"PROJECT",id:"one",name:"One"},{kind:"PROJECT",id:"two",name:"Two"}]:[]);
  if(o.method==="POST"){posts.push({url,options:o,body:JSON.parse(o.body)});return new Promise(resolve=>pending.push({url,resolve}));}
  if(url.includes("/history?"))return ok([{...version,topic:version.body.topic}]);
  return new Promise(resolve=>pending.push({url,resolve}));
 }}));return {nodes,document,window,pending,posts};
}
async function scope(h,value="PROJECT:one"){h.document.querySelector("#memory-scope").value=value;return h.window.memoryPanel.select();}
(async()=>{
 const h=harness();await tick();scope(h);h.pending.at(-1).resolve(ok([]));await tick();
 const opened=h.window.memoryPanel.open("memory");h.pending.at(-1).resolve(ok(version));await opened;
 assert.match(h.document.querySelector("#memory-evidence").textContent,/<script>snapshot<\/script>/);
 const flatten=e=>e.children.flatMap(c=>[c,...flatten(c)]);
 assert.equal(flatten(h.document.querySelector("#memory-evidence")).some(e=>e.href?.startsWith("javascript:")),false);
 assert.match(h.document.querySelector("#memory-review-label").textContent,/PROJECT.*one.*1.*exact-hash/);
 h.document.querySelector("#memory-reviewed").checked=true;h.document.querySelector("#memory-reviewed").events.change();
 const confirmation=h.window.memoryPanel.action("confirm");assert.equal(h.posts.at(-1).body.bodySha256,"exact-hash");assert.equal(h.posts.at(-1).body.revision,1);assert.equal(h.posts.at(-1).options.headers["X-Triage-Memory"],"1");
 scope(h,"PROJECT:two");h.pending.at(-1).resolve(ok([]));await tick();
 h.pending.find(p=>p.url.endsWith("/confirm")).resolve(ok({...version,revision:2,status:"CONFIRMED"}));await confirmation;
 assert.equal(h.document.querySelector("#memory-topic").value,"");assert.equal(h.document.querySelector("#memory-reviewed").checked,false);
 scope(h);h.pending.at(-1).resolve(ok([]));await tick();
 const stale=h.window.memoryPanel.open("memory");const old=h.pending.at(-1);h.document.querySelector("#memory-content").value="new draft";h.document.querySelector("#memory-content").events.input();old.resolve(ok(version));await stale;
 assert.equal(h.document.querySelector("#memory-content").value,"new draft");
 const reopen=h.window.memoryPanel.open("memory");h.pending.at(-1).resolve(ok(version));await reopen;
 h.document.querySelector("#memory-reviewed").checked=true;h.document.querySelector("#memory-reviewed").events.change();
 h.document.querySelector("#memory-topic").events.input();assert.equal(h.document.querySelector("#memory-reviewed").checked,false);assert.equal(h.document.querySelector("#memory-confirm").disabled,true);
 const oldList=h.window.memoryPanel.refresh();const oldRequest=h.pending.at(-1);scope(h,"PROJECT:two");h.pending.at(-1).resolve(ok([]));await tick();oldRequest.resolve(ok([{...version,topic:"old scope"}]));await oldList;
 assert.doesNotMatch(h.document.querySelector("#memory-list").textContent,/old scope/);
 const historical=harness("?scopeKind=PROJECT&scopeId=one&memory=memory&memoryRevision=1",false);await tick();
 assert.match(historical.pending.at(-1).url,/versions\/1\?scopeKind=PROJECT&scopeId=one/);
 historical.pending.at(-1).resolve(ok(version));await tick();
 assert.match(historical.document.querySelector("#memory-content").value,/<script>/);
 assert.match(historical.document.querySelector("#memory-scope-note").textContent,/登记已移除/);
 assert.equal(historical.document.querySelector("#memory-save").disabled,true);
 assert.equal(historical.document.querySelector("#memory-confirm").disabled,true);
 const candidate=harness("?scopeKind=PROJECT&scopeId=one&memory=memory");await tick();
 assert.match(candidate.pending.at(-1).url,/\/memory\?scopeKind=PROJECT&scopeId=one/);
 candidate.pending.at(-1).resolve(ok(version));await tick();
 assert.match(candidate.document.querySelector("#memory-content").value,/<script>/);
 assert.equal(candidate.document.querySelector("#memory-status").value,"ALL");
 const oldBinding=harness("?scopeKind=PROJECT&scopeId=one&memory=memory");await tick();oldBinding.pending.at(-1).resolve(ok({...version,scope:{kind:"PROJECT",id:"one",service:"old-service"}}));await tick();
 assert.equal(oldBinding.document.querySelector("#memory-save").disabled,true);assert.equal(oldBinding.document.querySelector("#memory-confirm").disabled,true);assert.equal(oldBinding.document.querySelector("#memory-disable").disabled,false);assert.match(oldBinding.document.querySelector("#memory-scope-note").textContent,/服务绑定已变化/);

 // A pending record selection cannot leave the old record actionable.
 const race=harness();await tick();const selecting=scope(race);race.pending.at(-1).resolve(ok([]));await selecting;
 async function showA(){const p=race.window.memoryPanel.open("memory");race.pending.at(-1).resolve(ok(version));await p;}
 const other={...version,id:"other",body:{...version.body,topic:"B selected"}};
 await showA();const openingB=race.window.memoryPanel.open("other"), bRequest=race.pending.at(-1), before=race.posts.length;
 const blockedSave=race.window.memoryPanel.save(), blockedAction=race.window.memoryPanel.action("disable");
 assert.equal(race.posts.length,before,"Old A must not submit save/action while B is loading");
 assert.equal(race.document.querySelector("#memory-save").disabled,true);assert.equal(race.document.querySelector("#memory-disable").disabled,true);
 bRequest.resolve(ok(other));await openingB;await blockedSave;await blockedAction;
 assert.equal(race.document.querySelector("#memory-topic").value,"B selected");

 // Mutations already in flight must stay bound to their original selection.
 await showA();const oldAction=race.window.memoryPanel.action("disable"), actionRequest=race.pending.at(-1);
 const selectingB=race.window.memoryPanel.open("other"), newerB=race.pending.at(-1);newerB.resolve(ok(other));await selectingB;
 actionRequest.resolve(ok({...version,revision:2,status:"DISABLED"}));await oldAction;
 assert.equal(race.document.querySelector("#memory-topic").value,"B selected","Late A action cannot replace B");
 await showA();race.document.querySelector("#memory-content").value="saved A edit";race.document.querySelector("#memory-content").events.input();
 const oldSave=race.window.memoryPanel.save(), saveRequest=race.pending.at(-1);
 const choosingB=race.window.memoryPanel.open("other"), selectedB=race.pending.at(-1);selectedB.resolve(ok(other));await choosingB;
 saveRequest.resolve(ok({...version,revision:2,body:{...version.body,content:"saved A edit"}}));await oldSave;
 assert.equal(race.document.querySelector("#memory-topic").value,"B selected","Late A save cannot replace B");

 // A newer mutation invalidates an older same-record read, in either response order.
 await showA();const mutation=race.window.memoryPanel.action("disable"), mutationRequest=race.pending.at(-1);
 const sameRead=race.window.memoryPanel.open("memory"), staleRead=race.pending.at(-1);
 mutationRequest.resolve(ok({...version,revision:2,status:"DISABLED",bodySha256:"new-hash"}));await mutation;
 staleRead.resolve(ok(version));await sameRead;
 assert.match(race.document.querySelector("#memory-detail-meta").textContent,/DISABLED.*v2.*new-hash/);
 await showA();const laterMutation=race.window.memoryPanel.action("disable"), laterMutationRequest=race.pending.at(-1);
 const earlyRead=race.window.memoryPanel.open("memory"), earlyReadRequest=race.pending.at(-1);earlyReadRequest.resolve(ok(version));await earlyRead;
 laterMutationRequest.resolve(ok({...version,revision:2,status:"DISABLED",bodySha256:"new-hash"}));await laterMutation;
 assert.match(race.document.querySelector("#memory-detail-meta").textContent,/DISABLED.*v2.*new-hash/);
 await showA();race.document.querySelector("#memory-content").value="newer saved body";race.document.querySelector("#memory-content").events.input();
 const newerSave=race.window.memoryPanel.save(), newerSaveRequest=race.pending.at(-1), oldSameRead=race.window.memoryPanel.open("memory"), oldSameReadRequest=race.pending.at(-1);
 newerSaveRequest.resolve(ok({...version,revision:2,body:{...version.body,content:"newer saved body"}}));await newerSave;oldSameReadRequest.resolve(ok(version));await oldSameRead;
 assert.equal(race.document.querySelector("#memory-content").value,"newer saved body","Older same-record GET cannot replace a successful save");
 console.log("Memory UI passed: safe text, exact version/hash, scope/draft guards, locked pending selection, late A save/action versus B, same-record GET versus newer save/action in both response orders");
})().catch(e=>{console.error(e);process.exitCode=1;});
