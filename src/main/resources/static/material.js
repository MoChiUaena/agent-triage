"use strict";
(() => {
  const $=selector=>document.querySelector(selector);
  const node=(tag,text)=>{const value=document.createElement(tag);if(text!=null)value.textContent=text;return value;};
  const parameters=new URLSearchParams(window.location.search);
  const types=["SERVICE","PR","ISSUE","CI","WORKFLOW"];
  let scopes=[],active=false,generation=0,scopeGeneration=0;
  $("#material-type").value=types.includes(parameters.get("type"))?parameters.get("type"):"SERVICE";
  $("#material-report-id").value=parameters.get("report")||"";
  async function request(url,options={}) {
    const response=await fetch(url,{cache:"no-store",...options});
    if(!response.ok){const body=await response.json().catch(()=>({}));throw new Error(body.detail||"读取材料失败 ("+response.status+")");}
    return response.json();
  }
  function message(text){$("#material-notice").textContent=text;}
  function reset(){
    generation++;$("#material-result").hidden=true;$("#material-text").textContent="";$("#material-recipient").textContent="";
    $("#material-references").replaceChildren();$("#material-omissions").replaceChildren();$("#material-hash").textContent="";
    $("#material-preview-button").disabled=false;message("");
  }
  function changeType(){
    reset(); const service=$("#material-type").value==="SERVICE", field=$("#material-scope");
    const previous=field.value, available=scopes.filter(scope=>service?scope.kind!=="REPOSITORY":scope.kind==="REPOSITORY");
    const empty=node("option","请选择明确范围");empty.value="";
    field.replaceChildren(empty,...available.map(scope=>{
      const option=node("option",({PROJECT:"项目",REPOSITORY:"仓库",SERVICE:"服务"}[scope.kind])+" · "+scope.name+(scope.service?" · "+scope.service:""));
      option.value=scope.kind+":"+scope.id;return option;
    }));
    const requested=parameters.get("repository")?"REPOSITORY:"+parameters.get("repository"):parameters.get("project")?"PROJECT:"+parameters.get("project"):parameters.get("service")?"SERVICE:"+parameters.get("service"):"";
    field.value=available.some(s=>s.kind+":"+s.id===previous)?previous:available.some(s=>s.kind+":"+s.id===requested)?requested:"";
  }
  async function setActive(value){
    active=value;const current=++scopeGeneration;reset();if(!active)return;
    try {const result=await request("/api/materials/scopes");if(current!==scopeGeneration||!active)return;scopes=result;changeType();}
    catch(error){if(current===scopeGeneration&&active)message(error.message);}
  }
  function render(preview){
    $("#material-result").hidden=false;
    const recipient=preview.recipient;
    $("#material-recipient").textContent=recipient.available
      ? recipient.displayName+" · "+recipient.model+"\n"+recipient.baseUrl+"\nProvider "+(recipient.providerId||"启动配置")+" · 配置版本 "+recipient.version+"\n"+recipient.message
      : recipient.displayName+" · "+recipient.message;
    $("#material-hash").textContent="正文 SHA256 "+preview.contentSha256+"\n过期时间 "+new Date(preview.expiresAt).toLocaleString("zh-CN",{hour12:false})+"\n确认状态：未确认；本页只预览，不调用模型。";
    $("#material-text").textContent=preview.text;
    $("#material-omissions").replaceChildren(...preview.omissions.map(text=>node("li",text)));
    $("#material-references").replaceChildren(...preview.materials.map(material=>{
      const ref=material.reference,card=node("li"),scope=ref.scope;
      card.append(node("strong",ref.type+" / "+ref.reportId),node("p","项目 "+(scope.projectId||"—")+" · 仓库 "+(scope.repositoryId||"—")+" · 服务 "+(scope.service||"—")),
        node("p","版本 "+ref.version+"\n快照 SHA256 "+ref.contentSha256+"\n读取于 "+new Date(ref.readAt).toLocaleString("zh-CN",{hour12:false})),node("p","纳入字段："+material.fields.join("、")));
      const link=node("a","打开原报告");
      if(/^\/(?:\?run=|development\.html\?)/.test(ref.originalLink))link.href=ref.originalLink;
      card.append(link);return card;
    }));
  }
  async function preview(){
    reset();if(!active)return;const current=generation;
    const selection={type:$("#material-type").value,reportId:$("#material-report-id").value.trim()};
    const scope=scopes.find(s=>s.kind+":"+s.id===$("#material-scope").value);
    if(!scope||!selection.reportId){message("请选择范围并填写已保存报告 ID。");return;}
    if(scope.kind==="PROJECT")selection.projectId=scope.id;
    else if(scope.kind==="REPOSITORY")selection.repositoryId=scope.id;
    else selection.service=scope.service;
    $("#material-preview-button").disabled=true;message("正在读取保存快照并生成发送预览…");
    try{
      const result=await request("/api/materials/previews",{method:"POST",headers:{"Content-Type":"application/json","X-Triage-Material":"1"},body:JSON.stringify({references:[selection]})});
      if(current!==generation||!active)return;render(result);message("已生成预览。模型、材料或源码版本变化后须重新预览。");
    }catch(error){if(current===generation&&active)message(error.message);}
    finally{if(current===generation)$("#material-preview-button").disabled=false;}
  }
  $("#material-form").addEventListener("submit",event=>{event.preventDefault();preview();});
  $("#material-type").addEventListener("change",changeType);
  $("#material-scope").addEventListener("change",reset);
  $("#material-report-id").addEventListener("input",reset);
  window.materialPanel={setActive,changeType,preview};
})();
