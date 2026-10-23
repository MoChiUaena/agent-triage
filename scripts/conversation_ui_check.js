"use strict";
const assert=require("node:assert/strict"),fs=require("node:fs"),vm=require("node:vm");
class Element {
 constructor(){this.children=[];this.text="";this.events={};this.value="";this.checked=false;this.disabled=false;this.hidden=false;this.dataset={};}
 set textContent(v){this.text=String(v??"");this.children=[];}get textContent(){return this.text+this.children.map(c=>c.textContent).join("");}
 set innerHTML(v){throw Error("Untrusted data must render safely");}
 append(...c){this.children.push(...c);}replaceChildren(...c){this.children=c;this.text="";}addEventListener(e,cb){this.events[e]=cb;}setAttribute(){}classList={toggle(){}};
}
const nodes=new Map(),$=s=>{if(!nodes.has(s))nodes.set(s,new Element());return nodes.get(s);};
const document={querySelector:$,createElement:()=>new Element()};let requests=[],resolvePreview,resolveSearch;
const model={available:true,providerId:"provider",version:1,displayName:"<model>",model:"stub",baseUrl:"http://localhost"};
const material={text:"<script>original</script>",reference:{type:"PR",reportId:"report",scope:{repositoryId:"repository"},contentSha256:"hash",version:"fixed",originalLink:"/development.html?report=report"},fields:["title"],omissions:["no source"]};
const detail={id:"one",revision:0,original:material,messages:[{role:"ASSISTANT",text:"<img onerror=x>",citations:[{id:"report:report",report:material}],documentsDerived:false}],attempts:[],model,notice:"snapshot",activeAttempt:null};
const prepared={context:{conversationId:"one",revision:0,question:"why",documentsRequired:true},preview:{id:"preview",recipient:model,text:"<script>preview</script>",contentSha256:"payload-hash",expiresAt:"2099-01-01T00:00:00Z",omissions:["history docs"]}};
const events={},window={location:{search:"?conversation=one",hash:"#conversations"},history:{replaceState(){}},addEventListener:(e,cb)=>events[e]=cb};
const fetch=async(url,options={})=>{requests.push({url,options});let value;if(url==="/api/conversations")value=[];else if(url==="/api/conversations/one")value=detail;else if(url.endsWith("/previews"))return new Promise(r=>resolvePreview=r);else if(url.startsWith("/api/knowledge/search"))return new Promise(r=>resolveSearch=r);else if(url.endsWith("/attempts"))value={...detail,activeAttempt:"attempt"};else if(url.endsWith("/references"))value={...detail,revision:1,messages:[...detail.messages,{role:"USER",text:"why",citations:[]}],attempts:[{state:"REFERENCE_ONLY",message:"未调用模型"}]};else throw Error(url);return{ok:true,json:async()=>value};};
const context=vm.createContext({window,document,URLSearchParams,Date,JSON,console,fetch,encodeURIComponent,setTimeout:()=>1,clearTimeout(){}});
vm.runInContext(fs.readFileSync("src/main/resources/static/conversation.js","utf8"),context);
(async()=>{
 await window.conversationPanel.setActive(true);
 assert.equal($("#conversation-origin").textContent,material.text);assert.match($("#conversation-messages").textContent,/<img onerror=x>/);
 $("#conversation-question").value="why";$("#conversation-use-model").checked=true;
 let work=window.conversationPanel.preview();$("#conversation-question").value="edited";$("#conversation-question").events.input();resolvePreview({ok:true,json:async()=>prepared});await work;
 assert.equal($("#conversation-preview").hidden,true,"Editing question discards in-flight stale preview");
 $("#conversation-question").value="why";work=window.conversationPanel.preview();resolvePreview({ok:true,json:async()=>prepared});await work;
 assert.equal($("#conversation-preview-text").textContent,prepared.preview.text);assert.equal($("#conversation-send").disabled,true);
 $("#conversation-confirm").checked=true;$("#conversation-confirm").events.change();assert.equal($("#conversation-send").disabled,true,"Generic confirmation cannot authorize document history");
 $("#conversation-documents-confirm").checked=true;$("#conversation-documents-confirm").events.change();assert.equal($("#conversation-send").disabled,false);
 $("#conversation-use-model").checked=false;$("#conversation-use-model").events.change();assert.equal($("#conversation-send").disabled,true);assert.equal($("#conversation-confirm").checked,false);
 $("#conversation-keyword").value="docs";work=window.conversationPanel.search();window.conversationPanel.setActive(false);resolveSearch({ok:true,json:async()=>({results:[{title:"stale",versionId:"v",chunkId:"c"}],coverage:"",truncated:false})});await work;
 assert.equal($("#conversation-hits").children.length,0,"Inactive response cannot repopulate selections");
 await window.conversationPanel.setActive(true);$("#conversation-question").value="why";await window.conversationPanel.reference();assert.match($("#conversation-attempts").textContent,/未调用模型/);assert.equal(requests.filter(r=>r.url.endsWith("/attempts")).length,0);
 console.log("Conversation UI checks passed: exact preview, XSS, stale response, consent invalidation, reference-only.");
})().catch(e=>{console.error(e);process.exitCode=1;});
